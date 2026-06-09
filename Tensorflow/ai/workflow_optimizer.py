"""
workflow_optimizer.py
Analiza el historial real de trámites de un workflow y genera recomendaciones
concretas de cómo modificar la estructura de nodos para reducir tiempos.

Fuentes de datos:
  - GET /api/workflows/{id}   → nodos + transiciones del workflow
  - MongoDB historial_tramites → camino real de cada trámite
  - MongoDB tramites          → estado final (COMPLETADO/RECHAZADO)
"""

import logging
from collections import defaultdict
from datetime import datetime, timezone

from core.api_client import api_get, get_mongo_db

logger = logging.getLogger(__name__)


# ── Helpers ───────────────────────────────────────────────────────────────
def _avg(lst): return sum(lst) / len(lst) if lst else 0
def _pct(a, b): return round(a / b * 100, 1) if b else 0


class WorkflowOptimizer:

    def analyze(self, workflow_id: str) -> dict:
        # 1. Estructura del workflow (nodos + transiciones)
        wf = self._load_workflow(workflow_id)
        if not wf:
            return {"error": "Workflow no encontrado"}

        nodes       = {n["id"]: n for n in wf.get("nodo", [])}
        transitions = wf.get("transitions", [])

        # 2. Tramites de este workflow
        db       = get_mongo_db()
        tramites = list(db["tramites"].find({"workflowId": workflow_id}))
        if not tramites:
            return {"error": "Sin trámites históricos para analizar"}

        tramite_ids    = [str(t["_id"]) for t in tramites]
        tramite_status = {str(t["_id"]): t.get("status", "") for t in tramites}

        # 3. Historial de todos esos tramites
        histories = list(db["historial_tramites"].find(
            {"tramiteId": {"$in": tramite_ids}}
        ).sort("changedAt", 1))

        # 4. Agrupar historial por tramite
        hist_by_tramite: dict[str, list] = defaultdict(list)
        for h in histories:
            hist_by_tramite[h["tramiteId"]].append(h)

        # 5. Calcular estadísticas por nodo
        node_stats = self._compute_node_stats(hist_by_tramite, tramite_status, nodes)

        # 6. Generar recomendaciones
        recommendations = self._generate_recommendations(
            nodes, transitions, node_stats, tramite_status, hist_by_tramite
        )

        # 8. Resumen general
        total    = len(tramites)
        completados = sum(1 for s in tramite_status.values() if s == "COMPLETADO")
        rechazados  = sum(1 for s in tramite_status.values() if s == "RECHAZADO")

        avg_duration_h = self._avg_completion_time(hist_by_tramite, tramite_status)

        return {
            "workflowId":   workflow_id,
            "workflowName": wf.get("name", ""),
            "summary": {
                "totalTramites":    total,
                "completados":      completados,
                "rechazados":       rechazados,
                "tasaExito":        f"{_pct(completados, total)}%",
                "tiempoPromedioH":  round(avg_duration_h, 1),
            },
            "nodeStats":       node_stats,
            "recommendations": recommendations,
        }

    # ── Carga workflow ────────────────────────────────────────────────────

    def _load_workflow(self, wf_id: str) -> dict | None:
        try:
            return api_get(f"/workflows/{wf_id}")
        except Exception as e:
            logger.error(f"No se pudo cargar workflow {wf_id}: {e}")
            return None

    # ── Estadísticas por nodo ─────────────────────────────────────────────

    def _compute_node_stats(self, hist_by_tramite, tramite_status, nodes) -> list[dict]:
        durations_by_node: dict[str, list[float]] = defaultdict(list)
        visits_by_node:    dict[str, int]          = defaultdict(int)
        completions_after: dict[str, int]          = defaultdict(int)
        rejections_after:  dict[str, int]          = defaultdict(int)

        for tramite_id, hist in hist_by_tramite.items():
            status = tramite_status.get(tramite_id, "")
            for entry in hist:
                nodo_id = entry.get("toNodoId")
                if not nodo_id:
                    continue
                visits_by_node[nodo_id] += 1
                dur = entry.get("durationInNodo")
                if dur and dur > 0:
                    durations_by_node[nodo_id].append(float(dur))
                if status == "COMPLETADO":
                    completions_after[nodo_id] += 1
                elif status == "RECHAZADO":
                    rejections_after[nodo_id] += 1

        result = []
        for nodo_id, nodo in nodes.items():
            node_type = (nodo.get("nodeType") or "").lower()
            if node_type in ("inicio", "fin", "start", "end"):
                continue

            avg_min_expected = nodo.get("avgMinutes") or 0
            durs             = durations_by_node.get(nodo_id, [])
            avg_min_real     = _avg(durs)
            visits           = visits_by_node.get(nodo_id, 0)
            over_ratio       = round(avg_min_real / avg_min_expected, 2) if avg_min_expected > 0 else None

            severity = "NORMAL"
            if over_ratio:
                if over_ratio >= 3.0:
                    severity = "CRITICO"
                elif over_ratio >= 1.8:
                    severity = "ALTO"
                elif over_ratio >= 1.3:
                    severity = "MEDIO"

            result.append({
                "nodoId":           nodo_id,
                "nodoName":         nodo.get("name", ""),
                "nodeType":         node_type,
                "visits":           visits,
                "avgMinutesExpected": avg_min_expected,
                "avgMinutesReal":   round(avg_min_real, 1),
                "overRatio":        over_ratio,
                "severity":         severity,
                "completionRate":   _pct(completions_after.get(nodo_id, 0), visits),
                "rejectionRate":    _pct(rejections_after.get(nodo_id, 0), visits),
            })

        return sorted(result, key=lambda x: (x["overRatio"] or 0), reverse=True)

    # ── Tiempo promedio de completado ─────────────────────────────────────

    def _avg_completion_time(self, hist_by_tramite, tramite_status) -> float:
        durations = []
        for tramite_id, hist in hist_by_tramite.items():
            if tramite_status.get(tramite_id) != "COMPLETADO":
                continue
            timestamps = [
                h["changedAt"] for h in hist
                if isinstance(h.get("changedAt"), datetime)
            ]
            if len(timestamps) >= 2:
                delta = (max(timestamps) - min(timestamps)).total_seconds() / 3600
                durations.append(delta)
        return _avg(durations)

    # ── Generador de recomendaciones ──────────────────────────────────────

    def _generate_recommendations(self, nodes, transitions, node_stats, tramite_status, hist_by_tramite) -> list[dict]:
        recs = []

        trans_from: dict[str, list] = defaultdict(list)
        trans_single: dict[str, str] = {}
        for t in transitions:
            trans_from[t.get("fromNodoId", "")].append(t)
        for fid, lst in trans_from.items():
            if len(lst) == 1:
                trans_single[fid] = lst[0].get("toNodoId", "")

        node_time    = {n["nodoId"]: n["avgMinutesReal"] for n in node_stats}
        stats_by_id  = {n["nodoId"]: n for n in node_stats}

        # ── 1. Cuellos de botella ─────────────────────────────────────────
        for ns in node_stats:
            if ns["severity"] in ("ALTO", "CRITICO") and ns["visits"] >= 2:
                extra_min = round(ns["avgMinutesReal"] - ns["avgMinutesExpected"], 1)
                impacto_h = round(extra_min * ns["visits"] / 60, 1)
                recs.append({
                    "tipo":         "CUELLO_DE_BOTELLA",
                    "prioridad":    "ALTA" if ns["severity"] == "CRITICO" else "MEDIA",
                    "nodoAfectado": ns["nodoName"],
                    "explicacion":  (
                        f"El nodo '{ns['nodoName']}' está tardando {ns['overRatio']}x más de lo "
                        f"estimado ({ns['avgMinutesReal']} min reales vs {ns['avgMinutesExpected']} min esperados). "
                        f"Generó {impacto_h}h de demora acumulada en {ns['visits']} trámites."
                    ),
                    "accion": (
                        f"Ajustá el avgMinutes a {int(ns['avgMinutesReal'])} min "
                        f"o redistribuí la tarea entre más responsables."
                    ),
                    "tiempoAhorradoEstimadoH": impacto_h,
                    "confianza": min(0.95, 0.6 + ns["visits"] * 0.05),
                })

        # ── 2. Bifurcación paralela ───────────────────────────────────────
        for nodo_id, outgoing_list in trans_from.items():
            if len(outgoing_list) < 2:
                continue
            nodo = nodes.get(nodo_id)
            if not nodo:
                continue
            if (nodo.get("nodeType") or "").lower() in ("bifurcasion", "bifurcacion"):
                continue

            children_names = [(nodes.get(t.get("toNodoId")) or {}).get("name", "?") for t in outgoing_list]
            child_times    = [node_time.get(t.get("toNodoId", ""), 0) for t in outgoing_list]

            if sum(child_times) > 0:
                serial_min   = sum(child_times)
                parallel_min = max(child_times)
                saved_min    = serial_min - parallel_min
                saved_h      = round(saved_min / 60, 1)

                if saved_min >= 30:
                    recs.append({
                        "tipo":         "AGREGAR_BIFURCACION",
                        "prioridad":    "ALTA" if saved_h >= 2 else "MEDIA",
                        "nodoAfectado": nodo.get("name", ""),
                        "explicacion":  (
                            f"Después de '{nodo.get('name', '')}', los nodos "
                            f"{' y '.join(repr(n) for n in children_names)} se ejecutan en serie "
                            f"({serial_min} min). Podrían ejecutarse en paralelo reduciendo a {parallel_min} min."
                        ),
                        "accion": (
                            f"Convertí '{nodo.get('name', '')}' en tipo bifurcación y conectá "
                            f"los nodos hijos en paralelo con un nodo de unión al final."
                        ),
                        "tiempoAhorradoEstimadoH": saved_h,
                        "confianza": 0.70,
                    })

        # ── 3. Reordenamiento de ruta (fail-fast) ────────────────────────
        # Construir cadena lineal de nodos en orden
        all_to  = {t.get("toNodoId") for t in transitions}
        starts  = [fid for fid in trans_single if fid not in all_to]
        chain: list[str] = []
        cur = starts[0] if starts else None
        seen: set = set()
        while cur and cur not in seen:
            seen.add(cur)
            n = nodes.get(cur)
            if n and (n.get("nodeType") or "").lower() not in ("inicio","fin","start","end"):
                chain.append(cur)
            cur = trans_single.get(cur)

        for i in range(len(chain) - 1):
            id_a = chain[i]
            id_b = chain[i + 1]
            sa   = stats_by_id.get(id_a)
            sb   = stats_by_id.get(id_b)
            if not sa or not sb:
                continue
            if sa["visits"] < 3 or sb["visits"] < 3:
                continue

            # fail-fast: B rechaza más y cuesta menos que A → conviene poner B antes
            if (sb["rejectionRate"] > sa["rejectionRate"] + 10
                    and sb["avgMinutesReal"] > 0
                    and sa["avgMinutesReal"] > sb["avgMinutesReal"] * 1.4):
                casos_rechazados = round(sa["visits"] * sb["rejectionRate"] / 100)
                saved_min = casos_rechazados * sa["avgMinutesReal"]
                saved_h   = round(saved_min / 60, 1)
                if saved_h >= 0.3:
                    recs.append({
                        "tipo":         "REORDENAR_RUTA",
                        "prioridad":    "ALTA" if saved_h >= 2 else "MEDIA",
                        "nodoAfectado": sa["nodoName"],
                        "explicacion":  (
                            f"'{sb['nodoName']}' rechaza el {sb['rejectionRate']}% de los trámites "
                            f"en solo {sb['avgMinutesReal']} min, pero está DESPUÉS de "
                            f"'{sa['nodoName']}' que tarda {sa['avgMinutesReal']} min. "
                            f"Se están procesando {casos_rechazados} trámites que van a ser rechazados "
                            f"gastando {sa['avgMinutesReal']} min innecesarios en '{sa['nodoName']}'."
                        ),
                        "accion": (
                            f"Cambiar conexión: ... → '{sb['nodoName']}' → '{sa['nodoName']}' → ... "
                            f"Poner primero el filtro más rápido y estricto ahorra ~{saved_h}h acumuladas."
                        ),
                        "conexionSugerida": {
                            "conectar":  sb["nodoName"],
                            "antes_de":  sa["nodoName"],
                            "razon":     f"fail-fast: '{sb['nodoName']}' filtra {sb['rejectionRate']}% en {sb['avgMinutesReal']} min vs {sa['avgMinutesReal']} min de '{sa['nodoName']}'"
                        },
                        "tiempoAhorradoEstimadoH": saved_h,
                        "confianza": min(0.90, 0.55 + sa["visits"] * 0.04),
                    })

        # ── 4. Ruta óptima observada ──────────────────────────────────────
        ruta_optima = self._find_fastest_route(hist_by_tramite, tramite_status, nodes)
        if ruta_optima:
            recs.append(ruta_optima)

        return sorted(recs, key=lambda r: {"ALTA": 0, "MEDIA": 1, "BAJA": 2}.get(r.get("prioridad","BAJA"), 2))

    # ── Ruta óptima observada ─────────────────────────────────────────────

    def _find_fastest_route(self, hist_by_tramite, tramite_status, nodes) -> dict | None:
        rutas: list[tuple[list[str], float]] = []  # (secuencia de nombres, horas)

        for tramite_id, hist in hist_by_tramite.items():
            if tramite_status.get(tramite_id) != "COMPLETADO":
                continue

            sorted_hist = sorted(hist, key=lambda h: h.get("changedAt") or datetime.min)
            secuencia   = []
            total_min   = 0.0

            for h in sorted_hist:
                nid = h.get("toNodoId")
                if not nid:
                    continue
                n = nodes.get(nid)
                if not n:
                    continue
                if (n.get("nodeType") or "").lower() in ("inicio","fin","start","end"):
                    continue
                secuencia.append(n.get("name", nid))
                dur = h.get("durationInNodo") or 0
                total_min += dur

            if secuencia and total_min > 0:
                rutas.append((secuencia, total_min / 60))

        if len(rutas) < 2:
            return None

        rutas.sort(key=lambda x: x[1])
        mas_rapida = rutas[0]
        mas_lenta  = rutas[-1]
        promedio_h = _avg([r[1] for r in rutas])

        return {
            "tipo":      "RUTA_OPTIMA",
            "prioridad": "MEDIA",
            "nodoAfectado": " → ".join(mas_rapida[0]),
            "explicacion": (
                f"La ruta más rápida observada completa el trámite en {round(mas_rapida[1], 1)}h: "
                f"{' → '.join(mas_rapida[0])}. "
                f"La más lenta tardó {round(mas_lenta[1], 1)}h. "
                f"Promedio general: {round(promedio_h, 1)}h."
            ),
            "accion": (
                f"Revisá si la secuencia '{' → '.join(mas_rapida[0])}' puede ser el camino estándar. "
                f"Eliminá pasos opcionales o mové nodos lentos al final del flujo."
            ),
            "conexionSugerida": {
                "secuencia_optima": mas_rapida[0],
                "tiempo_optimo_h":  round(mas_rapida[1], 1),
                "tiempo_promedio_h": round(promedio_h, 1),
                "ahorro_vs_lento_h": round(mas_lenta[1] - mas_rapida[1], 1),
            },
            "tiempoAhorradoEstimadoH": round(mas_lenta[1] - mas_rapida[1], 1),
            "confianza": min(0.85, 0.5 + len(rutas) * 0.05),
        }
