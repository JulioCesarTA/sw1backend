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
import os
import requests
from collections import defaultdict
from datetime import datetime, timezone

from pymongo import MongoClient

logger = logging.getLogger(__name__)

SPRING_API      = os.getenv("SPRING_API_URL", "http://localhost:8080/api")
SPRING_EMAIL    = os.getenv("SPRING_EMAIL",   "juan@gmail.com")
SPRING_PASSWORD = os.getenv("SPRING_PASSWORD","julioavila")
MONGO_URI       = os.getenv("MONGODB_URI",    "mongodb://localhost:27017")
MONGO_DB        = os.getenv("MONGODB_DB",     "workflow_db")

_token: str = ""


# ── Auth ──────────────────────────────────────────────────────────────────
def _get_token() -> str:
    global _token
    try:
        r = requests.post(f"{SPRING_API}/auth/login",
                          json={"email": SPRING_EMAIL, "password": SPRING_PASSWORD},
                          timeout=8)
        r.raise_for_status()
        d = r.json()
        _token = d.get("accessToken") or d.get("token") or ""
    except Exception as e:
        logger.warning(f"WorkflowOptimizer auth: {e}")
    return _token

def _headers() -> dict:
    tok = _token or _get_token()
    return {"Authorization": f"Bearer {tok}"} if tok else {}


# ── MongoDB ───────────────────────────────────────────────────────────────
def _get_db():
    client = MongoClient(MONGO_URI, serverSelectionTimeoutMS=8000)
    return client[MONGO_DB]


# ── Helpers ───────────────────────────────────────────────────────────────
def _avg(lst): return sum(lst) / len(lst) if lst else 0
def _pct(a, b): return round(a / b * 100, 1) if b else 0


class WorkflowOptimizer:

    def analyze(self, workflow_id: str) -> dict:
        _get_token()

        # 1. Estructura del workflow (nodos + transiciones)
        wf = self._load_workflow(workflow_id)
        if not wf:
            return {"error": "Workflow no encontrado"}

        nodes       = {n["id"]: n for n in wf.get("nodo", [])}
        transitions = wf.get("transitions", [])

        # 2. Tramites de este workflow
        db       = _get_db()
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

        # 6. Calcular estadísticas por transición (caminos)
        path_stats = self._compute_path_stats(hist_by_tramite, tramite_status, nodes, transitions)

        # 7. Generar recomendaciones
        recommendations = self._generate_recommendations(
            nodes, transitions, node_stats, path_stats, tramite_status
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
            "nodeStats":        node_stats,
            "pathStats":        path_stats,
            "recommendations":  recommendations,
        }

    # ── Carga workflow ────────────────────────────────────────────────────

    def _load_workflow(self, wf_id: str) -> dict | None:
        try:
            r = requests.get(f"{SPRING_API}/workflows/{wf_id}",
                             headers=_headers(), timeout=10)
            if r.status_code == 401:
                _get_token()
                r = requests.get(f"{SPRING_API}/workflows/{wf_id}",
                                 headers=_headers(), timeout=10)
            r.raise_for_status()
            return r.json()
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

    # ── Estadísticas por camino (transición) ──────────────────────────────

    def _compute_path_stats(self, hist_by_tramite, tramite_status, nodes, transitions) -> list[dict]:
        # Construir mapa de transiciones para lookup rápido
        trans_map: dict[tuple, dict] = {}
        for t in transitions:
            key = (t.get("fromNodoId"), t.get("toNodoId"))
            trans_map[key] = t

        path_counts:     dict[tuple, int]         = defaultdict(int)
        path_completions: dict[tuple, int]         = defaultdict(int)
        path_durations:  dict[tuple, list[float]] = defaultdict(list)

        for tramite_id, hist in hist_by_tramite.items():
            status = tramite_status.get(tramite_id, "")
            for entry in hist:
                frm = entry.get("fromNodoId")
                to  = entry.get("toNodoId")
                if not frm or not to:
                    continue
                key = (frm, to)
                path_counts[key] += 1
                if status == "COMPLETADO":
                    path_completions[key] += 1
                dur = entry.get("durationInNodo")
                if dur and dur > 0:
                    path_durations[key].append(float(dur))

        result = []
        for (frm, to), count in path_counts.items():
            trans_info = trans_map.get((frm, to), {})
            result.append({
                "fromNodoId":    frm,
                "fromNodoName":  (nodes.get(frm) or {}).get("name", frm),
                "toNodoId":      to,
                "toNodoName":    (nodes.get(to) or {}).get("name", to),
                "transitionName": trans_info.get("name", ""),
                "count":         count,
                "completionRate": _pct(path_completions.get((frm, to), 0), count),
                "avgMinutes":    round(_avg(path_durations.get((frm, to), [])), 1),
            })

        return sorted(result, key=lambda x: x["count"], reverse=True)

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

    def _generate_recommendations(self, nodes, transitions, node_stats,
                                   path_stats, tramite_status) -> list[dict]:
        recs = []
        total = len(tramite_status)

        node_map  = {n["nodoId"]: n for n in node_stats}
        path_map  = {(p["fromNodoId"], p["toNodoId"]): p for p in path_stats}

        # Mapa de transiciones para análisis estructural
        trans_from: dict[str, list] = defaultdict(list)  # fromNodoId → [transitions]
        trans_to:   dict[str, list] = defaultdict(list)  # toNodoId → [transitions]
        for t in transitions:
            trans_from[t.get("fromNodoId", "")].append(t)
            trans_to[t.get("toNodoId", "")].append(t)

        # ── 1. Cuellos de botella ─────────────────────────────────────────
        for ns in node_stats:
            if ns["severity"] in ("ALTO", "CRITICO") and ns["visits"] >= 2:
                over = ns["overRatio"]
                extra_min = round(ns["avgMinutesReal"] - ns["avgMinutesExpected"], 1)
                impacto_h = round(extra_min * ns["visits"] / 60, 1)
                recs.append({
                    "tipo":       "CUELLO_DE_BOTELLA",
                    "prioridad":  "ALTA" if ns["severity"] == "CRITICO" else "MEDIA",
                    "nodoAfectado": ns["nodoName"],
                    "explicacion": (
                        f"El nodo '{ns['nodoName']}' está tardando {over}x más de lo "
                        f"estimado ({ns['avgMinutesReal']} min reales vs "
                        f"{ns['avgMinutesExpected']} min esperados). "
                        f"En total ha generado {impacto_h}h de demora acumulada "
                        f"en {ns['visits']} trámites."
                    ),
                    "accion": (
                        f"Revisá la carga de trabajo asignada a este nodo. "
                        f"Si el tiempo real es consistentemente mayor al estimado, "
                        f"ajustá el avgMinutes a {int(ns['avgMinutesReal'])} min "
                        f"o redistribuí la tarea entre más responsables."
                    ),
                    "tiempoAhorradoEstimadoH": impacto_h,
                    "confianza": min(0.95, 0.6 + ns["visits"] * 0.05),
                })

        # ── 2. Nodos de decisión con rama dominante ───────────────────────
        decision_nodes = [n for n in nodes.values()
                          if (n.get("nodeType") or "").lower() == "decision"]
        for dn in decision_nodes:
            dn_id    = dn["id"]
            outgoing = trans_from.get(dn_id, [])
            if len(outgoing) < 2:
                continue

            branch_stats = []
            for t in outgoing:
                key   = (dn_id, t.get("toNodoId"))
                pdata = path_map.get(key, {})
                branch_stats.append({
                    "label":      t.get("name", ""),
                    "toNodo":     (nodes.get(t.get("toNodoId", "")) or {}).get("name", ""),
                    "toNodoId":   t.get("toNodoId", ""),
                    "count":      pdata.get("count", 0),
                    "completion": pdata.get("completionRate", 0),
                    "avgMin":     pdata.get("avgMinutes", 0),
                })

            total_branch = sum(b["count"] for b in branch_stats)
            if total_branch < 2:
                continue

            for b in branch_stats:
                b["pct"] = _pct(b["count"], total_branch)

            # Rama dominante (>= 75% de los casos)
            dominant = max(branch_stats, key=lambda b: b["count"])
            if dominant["pct"] >= 75 and len(branch_stats) == 2:
                other = [b for b in branch_stats if b != dominant][0]
                recs.append({
                    "tipo":       "DECISION_DESBALANCEADA",
                    "prioridad":  "MEDIA",
                    "nodoAfectado": dn.get("name", ""),
                    "explicacion": (
                        f"En el nodo de decisión '{dn.get('name', '')}', "
                        f"el {dominant['pct']}% de los trámites siempre toma "
                        f"el camino '{dominant['label']}' hacia '{dominant['toNodo']}'. "
                        f"Solo el {other['pct']}% va por '{other['label']}'. "
                        f"Esto sugiere que la condición de decisión podría simplificarse."
                    ),
                    "accion": (
                        f"Considerá si el nodo de decisión sigue siendo necesario. "
                        f"Si el {dominant['pct']}% siempre toma el mismo camino, "
                        f"podés conectar directamente al nodo '{dominant['toNodo']}' "
                        f"y mover la excepción a un proceso separado. "
                        f"Esto eliminaría un punto de espera innecesario para la mayoría."
                    ),
                    "tiempoAhorradoEstimadoH": None,
                    "confianza": round(dominant["pct"] / 100, 2),
                })

        # ── 3. Nodos candidatos a bifurcación (paralelo) ──────────────────
        # Detectar nodos consecutivos que no dependen entre sí
        for nodo_id, outgoing_list in trans_from.items():
            if len(outgoing_list) < 2:
                continue
            nodo = nodes.get(nodo_id)
            if not nodo:
                continue
            ntype = (nodo.get("nodeType") or "").lower()
            # Si ya es bifurcación, no recomendar de nuevo
            if ntype in ("bifurcasion", "bifurcacion"):
                continue

            children_names = [
                (nodes.get(t.get("toNodoId")) or {}).get("name", "?")
                for t in outgoing_list
            ]
            # Calcular si ahorraría tiempo hacerlos en paralelo
            child_times = []
            for t in outgoing_list:
                key   = (nodo_id, t.get("toNodoId", ""))
                pdata = path_map.get(key, {})
                child_times.append(pdata.get("avgMinutes", 0))

            if len(child_times) >= 2 and sum(child_times) > 0:
                serial_min   = sum(child_times)
                parallel_min = max(child_times)
                saved_min    = serial_min - parallel_min
                saved_h      = round(saved_min / 60, 1)

                if saved_min >= 30:  # Solo recomendar si ahorra al menos 30 min
                    recs.append({
                        "tipo":       "AGREGAR_BIFURCACION",
                        "prioridad":  "ALTA" if saved_h >= 2 else "MEDIA",
                        "nodoAfectado": nodo.get("name", ""),
                        "explicacion": (
                            f"Después del nodo '{nodo.get('name', '')}', los nodos "
                            f"{' y '.join(repr(n) for n in children_names)} se ejecutan "
                            f"de forma secuencial ({serial_min} min en total). "
                            f"Estos nodos podrían ejecutarse en paralelo ya que no dependen "
                            f"entre sí, reduciendo el tiempo a {parallel_min} min."
                        ),
                        "accion": (
                            f"Convertí el nodo '{nodo.get('name', '')}' en tipo "
                            f"'bifurcación' o agregá un nodo de bifurcación después de él. "
                            f"Conectá todos los nodos hijos a ese bifurcador y luego "
                            f"agregá un nodo de unión al final para esperar que ambos completen."
                        ),
                        "tiempoAhorradoEstimadoH": saved_h,
                        "confianza": 0.70,
                    })

        # ── 4. Nodos casi nunca visitados (candidatos a eliminar) ──────────
        for ns in node_stats:
            if ns["visits"] == 0:
                continue
            visit_pct = _pct(ns["visits"], total)
            if visit_pct <= 10 and ns["visits"] >= 1:
                recs.append({
                    "tipo":       "NODO_POCO_USADO",
                    "prioridad":  "BAJA",
                    "nodoAfectado": ns["nodoName"],
                    "explicacion": (
                        f"El nodo '{ns['nodoName']}' solo fue visitado en el "
                        f"{visit_pct}% de los trámites ({ns['visits']} de {total}). "
                        f"Posiblemente sea un nodo de excepción o esté desconectado del flujo principal."
                    ),
                    "accion": (
                        f"Verificá si este nodo sigue siendo necesario. "
                        f"Si raramente se usa, considerá moverlo a un sub-proceso "
                        f"separado o eliminarlo para simplificar el diagrama."
                    ),
                    "tiempoAhorradoEstimadoH": None,
                    "confianza": 0.55,
                })

        # ── 5. Nodos con alta tasa de rechazo ─────────────────────────────
        for ns in node_stats:
            if ns["visits"] >= 3 and ns["rejectionRate"] >= 40:
                recs.append({
                    "tipo":       "ALTA_TASA_RECHAZO",
                    "prioridad":  "ALTA",
                    "nodoAfectado": ns["nodoName"],
                    "explicacion": (
                        f"El {ns['rejectionRate']}% de los trámites que pasan por "
                        f"'{ns['nodoName']}' terminan siendo rechazados. "
                        f"Esto indica que los trámites llegan a este nodo sin cumplir "
                        f"los requisitos necesarios."
                    ),
                    "accion": (
                        f"Agregá un nodo de validación o decisión ANTES de "
                        f"'{ns['nodoName']}' que verifique los requisitos mínimos. "
                        f"Así los trámites incompletos se devuelven al inicio del flujo "
                        f"antes de llegar a esta etapa crítica."
                    ),
                    "tiempoAhorradoEstimadoH": None,
                    "confianza": min(0.90, 0.5 + ns["visits"] * 0.05),
                })

        return sorted(recs, key=lambda r: {"ALTA": 0, "MEDIA": 1, "BAJA": 2}[r["prioridad"]])
