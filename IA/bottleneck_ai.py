from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
from typing import Any

from fastapi import HTTPException

from diagram_ai import HAIKU_MODEL, call_claude, get_db, parse_json_response, to_json


BOTTLENECK_SYSTEM_PROMPT = """
Eres un experto en optimizacion de procesos y analisis de workflows.
Debes detectar cuellos de botella usando primero los KPI y metricas ya calculadas desde la base de datos.
Responde SIEMPRE con JSON puro, sin markdown, con esta estructura:
{
  "summary": "resumen ejecutivo breve",
  "bottlenecks": [
    {
      "nodoId": "id",
      "nodoName": "nombre",
      "type": "delay|overload|fan_in|critical_path|rework|parallelization",
      "severity": "alta|media|baja",
      "reason": "explicacion concreta usando KPI y nombres reales de campos",
      "recommendation": "accion recomendada"
    }
  ],
  "parallelizationOpportunities": [
    "oportunidad concreta"
  ]
}
No recalcules KPI inventando campos. Usa los nombres reales recibidos.
Si no detectas problemas graves, igualmente devuelve summary y listas vacias o minimas.
"""


def analyze_bottlenecks(body: dict[str, Any]) -> dict[str, Any]:
    workflow_id = str(body.get("workflowId") or "").strip()
    workflow_name = str(body.get("workflowName") or "").strip()

    analytics = build_analytics(workflow_id, workflow_name, body)
    local_result = {
        "summary": analytics["summary"],
        "kpis": analytics["kpis"],
        "nodeMetrics": analytics["nodeMetrics"],
        "bottlenecks": analytics["localBottlenecks"],
        "parallelizationOpportunities": analytics["parallelizationOpportunities"],
    }

    prompt = build_bottleneck_prompt(body, analytics)
    try:
        response = call_claude(BOTTLENECK_SYSTEM_PROMPT, HAIKU_MODEL, 4096, [{"role": "user", "content": prompt}])
        parsed = parse_json_response(response)
    except HTTPException:
        return local_result
    except Exception:
        return local_result

    ai_bottlenecks = parsed.get("bottlenecks", [])
    if not isinstance(ai_bottlenecks, list):
        ai_bottlenecks = []

    node_metric_by_id = {
        str(item.get("nodoId")): item
        for item in analytics["nodeMetrics"]
        if item.get("nodoId")
    }

    merged_bottlenecks = []
    for item in ai_bottlenecks:
        if not isinstance(item, dict):
            continue
        merged = dict(item)
        node_metrics = node_metric_by_id.get(str(merged.get("nodoId") or ""))
        if node_metrics:
            merged.setdefault("nodoName", node_metrics.get("nodoName"))
            merged["TiempoEsperaMinutes"] = node_metrics.get("TiempoEsperaMinutes")
            merged["TiempoPromedioActividadEnNodoMinutes"] = node_metrics.get("TiempoPromedioActividadEnNodoMinutes")
            merged["activeCount"] = node_metrics.get("activeCount")
            merged["completedSamples"] = node_metrics.get("completedSamples")
            merged["avgMinutesTarget"] = node_metrics.get("avgMinutesTarget")
        if not merged.get("reason") and merged.get("description"):
            merged["reason"] = merged.get("description")
        merged_bottlenecks.append(merged)

    parallelization = normalize_parallelization(parsed.get("parallelizationOpportunities"))
    if not merged_bottlenecks:
        merged_bottlenecks = analytics["localBottlenecks"]
    if not parallelization:
        parallelization = analytics["parallelizationOpportunities"]

    summary = str(parsed.get("summary") or "").strip() or analytics["summary"]

    return {
        "summary": summary,
        "kpis": analytics["kpis"],
        "nodeMetrics": analytics["nodeMetrics"],
        "bottlenecks": merged_bottlenecks,
        "parallelizationOpportunities": parallelization,
    }


def build_analytics(workflow_id: str, workflow_name: str, body: dict[str, Any]) -> dict[str, Any]:
    nodos = list(body.get("nodo") or [])
    transitions = list(body.get("transitions") or [])
    tramites: list[dict[str, Any]] = []
    histories: list[dict[str, Any]] = []

    if workflow_id:
        try:
            db = get_db()
            nodos = list(db.workflow_nodo.find({"workflowId": workflow_id}))
            transitions = list(db.workflow_transitions.find({"workflowId": workflow_id}))
            tramites = list(db.tramites.find({"workflowId": workflow_id}))
            tramite_ids = [str(item.get("_id")) for item in tramites if item.get("_id") is not None]
            if tramite_ids:
                histories = list(
                    db.historial_tramites.find({"tramiteId": {"$in": tramite_ids}})
                    .sort([("tramiteId", 1), ("changedAt", 1)])
                )
        except Exception:
            tramites = []
            histories = []

    for item in nodos:
        if item.get("_id") is not None and not item.get("id"):
            item["id"] = str(item["_id"])
    for item in transitions:
        if item.get("_id") is not None and not item.get("id"):
            item["id"] = str(item["_id"])

    nodo_by_id = {
        str(item.get("id") or item.get("_id")): item
        for item in nodos
        if item.get("id") is not None or item.get("_id") is not None
    }

    histories_by_tramite: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for history in histories:
        tramite_id = str(history.get("tramiteId") or "")
        if tramite_id:
            histories_by_tramite[tramite_id].append(history)

    metric_by_nodo: dict[str, dict[str, Any]] = {}
    for nodo_id, nodo in nodo_by_id.items():
        if not is_trackable_nodo(nodo):
            continue
        metric_by_nodo[nodo_id] = {
            "nodoId": nodo_id,
            "nodoName": nodo.get("name") or nodo_id,
            "avgMinutesTarget": int_or_zero(nodo.get("avgMinutes")),
            "incomingCount": count_incoming(nodo_id, transitions),
            "outgoingCount": count_outgoing(nodo_id, transitions),
            "waitTotal": 0.0,
            "waitSamples": 0,
            "stayTotal": 0.0,
            "staySamples": 0,
            "activeCount": 0,
        }

    tiempo_espera_values: list[float] = []
    tiempo_en_nodo_values: list[float] = []
    tiempo_resolucion_values: list[float] = []
    now = datetime.now(timezone.utc)

    for tramite in tramites:
        tramite_id = str(tramite.get("_id") or "")
        tramite_history = histories_by_tramite.get(tramite_id, [])

        for index, current in enumerate(tramite_history):
            to_nodo_id = str(current.get("toNodoId") or "")
            metric = metric_by_nodo.get(to_nodo_id)
            if not metric:
                continue
            current_changed_at = normalize_dt(current.get("changedAt"))
            next_changed_at = normalize_dt(tramite_history[index + 1].get("changedAt")) if index + 1 < len(tramite_history) else None
            if current_changed_at is None or next_changed_at is None:
                continue
            minutes_in_nodo = minutes_between(current_changed_at, next_changed_at)
            if minutes_in_nodo < 0:
                continue
            metric["stayTotal"] += minutes_in_nodo
            metric["staySamples"] += 1
            tiempo_en_nodo_values.append(minutes_in_nodo)

        current_nodo_id = str(tramite.get("currentNodoId") or "")
        current_metric = metric_by_nodo.get(current_nodo_id)
        status = str(tramite.get("status") or "")
        if current_metric and status in {"PENDIENTE", "EN_PROGRESO"}:
            entered_at = resolve_entered_at(current_nodo_id, tramite_history, normalize_dt(tramite.get("createdAt")))
            if entered_at is not None:
                wait_minutes = minutes_between(entered_at, now)
                if wait_minutes >= 0:
                    current_metric["waitTotal"] += wait_minutes
                    current_metric["waitSamples"] += 1
                    current_metric["activeCount"] += 1
                    tiempo_espera_values.append(wait_minutes)

        if status == "COMPLETADO":
            created_at = normalize_dt(tramite.get("createdAt"))
            updated_at = normalize_dt(tramite.get("updatedAt"))
            if created_at is not None and updated_at is not None:
                resolution_minutes = minutes_between(created_at, updated_at)
                if resolution_minutes >= 0:
                    tiempo_resolucion_values.append(resolution_minutes)

    node_metrics = []
    for metric in metric_by_nodo.values():
        avg_wait = average(metric["waitTotal"], metric["waitSamples"])
        avg_stay = average(metric["stayTotal"], metric["staySamples"])
        metric_map = {
            "nodoId": metric["nodoId"],
            "nodoName": metric["nodoName"],
            "avgMinutesTarget": metric["avgMinutesTarget"],
            "incomingCount": metric["incomingCount"],
            "outgoingCount": metric["outgoingCount"],
            "activeCount": metric["activeCount"],
            "completedSamples": metric["staySamples"],
            "TiempoEsperaMinutes": round2(avg_wait),
            "TiempoEsperaDisplay": format_minutes(avg_wait),
            "TiempoPromedioActividadEnNodoMinutes": round2(avg_stay),
            "TiempoPromedioActividadEnNodoDisplay": format_minutes(avg_stay),
        }
        node_metrics.append(metric_map)

    node_metrics.sort(
        key=lambda item: (
            -pressure_score(item["TiempoEsperaMinutes"], item["TiempoPromedioActividadEnNodoMinutes"], item["avgMinutesTarget"], item["activeCount"]),
            item["nodoName"],
        )
    )

    kpis = [
        build_kpi(
            "TiempoEspera",
            average_from_values(tiempo_espera_values),
            len(tiempo_espera_values),
            "now - historial_tramites.changedAt",
            ["tramites.currentNodoId", "historial_tramites.changedAt"],
            "Promedio del tiempo que esperan las actividades activas en su nodo actual.",
        ),
        build_kpi(
            "TiempoPromedioActividadEnNodo",
            average_from_values(tiempo_en_nodo_values),
            len(tiempo_en_nodo_values),
            "next(historial_tramites.changedAt) - historial_tramites.changedAt",
            ["historial_tramites.toNodoId", "historial_tramites.changedAt"],
            "Promedio historico de permanencia antes de salir de un nodo.",
        ),
        build_kpi(
            "TiempoPromedioResolucion",
            average_from_values(tiempo_resolucion_values),
            len(tiempo_resolucion_values),
            "tramites.updatedAt - tramites.createdAt",
            ["tramites.updatedAt", "tramites.createdAt"],
            "Promedio de resolucion de tramites completados usando los nombres reales de tu BD.",
        ),
    ]

    local_bottlenecks = build_local_bottlenecks(node_metrics)
    parallelization = build_parallelization_opportunities(node_metrics, transitions, nodo_by_id)
    summary = build_summary_text(kpis, local_bottlenecks, workflow_name or workflow_id)

    return {
        "workflowId": workflow_id,
        "workflowName": workflow_name,
        "kpis": kpis,
        "nodeMetrics": node_metrics,
        "localBottlenecks": local_bottlenecks,
        "parallelizationOpportunities": parallelization,
        "summary": summary,
        "dbFieldNames": {
            "receivedAt": "tramites.createdAt",
            "completedAt": "tramites.updatedAt",
            "enteredNodoAt": "historial_tramites.changedAt",
            "currentNodoId": "tramites.currentNodoId",
        },
        "summaryStats": {
            "totalTramites": len(tramites),
            "activeTramites": sum(1 for item in tramites if str(item.get("status") or "") in {"PENDIENTE", "EN_PROGRESO"}),
            "completedTramites": sum(1 for item in tramites if str(item.get("status") or "") == "COMPLETADO"),
            "rejectedTramites": sum(1 for item in tramites if str(item.get("status") or "") == "RECHAZADO"),
            "trackableNodes": len(metric_by_nodo),
        },
    }


def build_bottleneck_prompt(body: dict[str, Any], analytics: dict[str, Any]) -> str:
    return (
        "Analiza este workflow y detecta cuellos de botella reales o potenciales.\n"
        "Usa primero los KPI calculados desde la base de datos y despues usa el diagrama para sugerir como arreglarlos.\n"
        "Cuando expliques formulas, usa los nombres reales de campos: tramites.createdAt, tramites.updatedAt, historial_tramites.changedAt y tramites.currentNodoId.\n\n"
        f"Workflow ID: {body.get('workflowId') or ''}\n"
        f"Workflow Name: {body.get('workflowName') or ''}\n"
        f"Nodos: {to_json(body.get('nodo') or [])}\n"
        f"Transitions: {to_json(body.get('transitions') or [])}\n"
        f"DB Field Names: {to_json(analytics.get('dbFieldNames') or {})}\n"
        f"Summary Stats: {to_json(analytics.get('summaryStats') or {})}\n"
        f"KPIs: {to_json(analytics.get('kpis') or [])}\n"
        f"Node Metrics: {to_json(analytics.get('nodeMetrics') or [])}\n"
        f"Local Bottlenecks: {to_json(analytics.get('localBottlenecks') or [])}\n"
        f"Parallelization Opportunities: {to_json(analytics.get('parallelizationOpportunities') or [])}\n\n"
        "Quiero recomendaciones practicas, concretas y aplicables al workflow."
    )


def build_kpi(label: str, average_minutes: float, sample_size: int, formula: str, source_fields: list[str], description: str) -> dict[str, Any]:
    return {
        "id": label,
        "label": label,
        "averageMinutes": round2(average_minutes),
        "displayValue": format_minutes(average_minutes),
        "sampleSize": sample_size,
        "formula": formula,
        "sourceFields": source_fields,
        "description": description,
    }


def build_local_bottlenecks(node_metrics: list[dict[str, Any]]) -> list[dict[str, Any]]:
    bottlenecks: list[dict[str, Any]] = []
    for item in node_metrics:
        wait_minutes = float(item.get("TiempoEsperaMinutes") or 0)
        stay_minutes = float(item.get("TiempoPromedioActividadEnNodoMinutes") or 0)
        target_minutes = float(item.get("avgMinutesTarget") or 0)
        active_count = int(item.get("activeCount") or 0)
        incoming_count = int(item.get("incomingCount") or 0)

        wait_ratio = (wait_minutes / target_minutes) if target_minutes > 0 else 0.0
        stay_ratio = (stay_minutes / target_minutes) if target_minutes > 0 else 0.0
        severity = resolve_severity(wait_ratio, stay_ratio, active_count, incoming_count)
        if severity is None:
            continue

        bottlenecks.append(
            {
                "nodoId": item.get("nodoId"),
                "nodoName": item.get("nodoName"),
                "type": resolve_type(wait_ratio, stay_ratio, active_count, incoming_count),
                "severity": severity,
                "reason": (
                    f"TiempoEspera {round2(wait_minutes)} min, "
                    f"TiempoPromedioActividadEnNodo {round2(stay_minutes)} min, "
                    f"avgMinutes {round2(target_minutes)} min, "
                    f"activas {active_count}, entradas {incoming_count}."
                ),
                "recommendation": build_recommendation(wait_ratio, stay_ratio, active_count, incoming_count),
                "TiempoEsperaMinutes": item.get("TiempoEsperaMinutes"),
                "TiempoPromedioActividadEnNodoMinutes": item.get("TiempoPromedioActividadEnNodoMinutes"),
                "activeCount": item.get("activeCount"),
                "completedSamples": item.get("completedSamples"),
                "avgMinutesTarget": item.get("avgMinutesTarget"),
            }
        )

    bottlenecks.sort(
        key=lambda item: (
            severity_rank(str(item.get("severity") or "")),
            -float(item.get("TiempoEsperaMinutes") or 0),
        )
    )
    return bottlenecks


def build_parallelization_opportunities(
    node_metrics: list[dict[str, Any]],
    transitions: list[dict[str, Any]],
    nodo_by_id: dict[str, dict[str, Any]],
) -> list[str]:
    opportunities: list[str] = []
    for item in node_metrics:
        active_count = int(item.get("activeCount") or 0)
        wait_minutes = float(item.get("TiempoEsperaMinutes") or 0)
        target_minutes = float(item.get("avgMinutesTarget") or 0)
        nodo_id = str(item.get("nodoId") or "")
        nodo_name = str(item.get("nodoName") or nodo_id)

        if active_count < 2 and wait_minutes <= target_minutes:
            continue

        outgoing = count_outgoing(nodo_id, transitions)
        incoming = count_incoming(nodo_id, transitions)
        if outgoing >= 2:
            opportunities.append(f"{nodo_name}: ya tiene ramas posibles; mueve prevalidaciones o tareas de soporte antes de este nodo.")
            continue
        if incoming >= 2:
            opportunities.append(f"{nodo_name}: recibe varias rutas; separa pre-chequeo, validacion y aprobacion en nodos distintos.")
            continue

        next_nodo_name = ""
        for transition in transitions:
            if str(transition.get("fromNodoId") or "") != nodo_id:
                continue
            next_nodo = nodo_by_id.get(str(transition.get("toNodoId") or ""))
            if next_nodo and is_trackable_nodo(next_nodo):
                next_nodo_name = str(next_nodo.get("name") or transition.get("toNodoId") or "")
                break
        if next_nodo_name:
            opportunities.append(f"{nodo_name}: divide la carga con {next_nodo_name} para que una sola actividad no concentre todo.")

    return list(dict.fromkeys(opportunities))


def build_summary_text(kpis: list[dict[str, Any]], bottlenecks: list[dict[str, Any]], workflow_name: str) -> str:
    tiempo_espera = find_kpi_display(kpis, "TiempoEspera")
    tiempo_en_nodo = find_kpi_display(kpis, "TiempoPromedioActividadEnNodo")
    tiempo_resolucion = find_kpi_display(kpis, "TiempoPromedioResolucion")
    if not bottlenecks:
        return (
            f"{workflow_name or 'Workflow'}: TiempoEspera {tiempo_espera}, "
            f"TiempoPromedioActividadEnNodo {tiempo_en_nodo} y "
            f"TiempoPromedioResolucion {tiempo_resolucion}. "
            "No se detectaron cuellos severos con las reglas locales."
        )
    top = bottlenecks[0]
    return (
        f"{workflow_name or 'Workflow'}: TiempoEspera {tiempo_espera}, "
        f"TiempoPromedioActividadEnNodo {tiempo_en_nodo} y "
        f"TiempoPromedioResolucion {tiempo_resolucion}. "
        f"El nodo mas presionado es {top.get('nodoName') or 'un nodo'} con severidad {top.get('severity') or 'media'}."
    )


def normalize_parallelization(raw_value: Any) -> list[str]:
    if not isinstance(raw_value, list):
        return []
    result: list[str] = []
    for item in raw_value:
        if isinstance(item, str) and item.strip():
            result.append(item.strip())
            continue
        if isinstance(item, dict):
            reason = str(item.get("reason") or "").strip()
            nodo_ids = item.get("nodoIds") or []
            prefix = ", ".join(str(node_id) for node_id in nodo_ids) + ": " if nodo_ids else ""
            if reason:
                result.append(prefix + reason)
    return result


def resolve_entered_at(current_nodo_id: str, history: list[dict[str, Any]], fallback: datetime | None) -> datetime | None:
    for item in reversed(history):
        if str(item.get("toNodoId") or "") == current_nodo_id:
            changed_at = normalize_dt(item.get("changedAt"))
            if changed_at is not None:
                return changed_at
    return fallback


def is_trackable_nodo(nodo: dict[str, Any] | None) -> bool:
    return str((nodo or {}).get("nodeType") or "").lower() == "proceso"


def count_incoming(nodo_id: str, transitions: list[dict[str, Any]]) -> int:
    return sum(1 for item in transitions if str(item.get("toNodoId") or "") == nodo_id)


def count_outgoing(nodo_id: str, transitions: list[dict[str, Any]]) -> int:
    return sum(1 for item in transitions if str(item.get("fromNodoId") or "") == nodo_id)


def normalize_dt(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def minutes_between(start: datetime, end: datetime) -> float:
    return (end - start).total_seconds() / 60.0


def average(total: float, count: int) -> float:
    return 0.0 if count <= 0 else total / count


def average_from_values(values: list[float]) -> float:
    return 0.0 if not values else sum(values) / len(values)


def int_or_zero(value: Any) -> int:
    try:
        return int(value or 0)
    except Exception:
        return 0


def round2(value: float) -> float:
    return round(float(value or 0), 2)


def format_minutes(value: float) -> str:
    minutes = float(value or 0)
    if minutes <= 0:
        return "0 min"
    if minutes >= 1440:
        return f"{round2(minutes / 1440.0)} d"
    if minutes >= 60:
        return f"{round2(minutes / 60.0)} h"
    return f"{round2(minutes)} min"


def pressure_score(wait_minutes: float, stay_minutes: float, target_minutes: float, active_count: int) -> float:
    wait_ratio = (wait_minutes / target_minutes) if target_minutes > 0 else wait_minutes
    stay_ratio = (stay_minutes / target_minutes) if target_minutes > 0 else stay_minutes
    return wait_ratio + stay_ratio + active_count


def resolve_severity(wait_ratio: float, stay_ratio: float, active_count: int, incoming_count: int) -> str | None:
    if wait_ratio >= 1.5 or stay_ratio >= 1.5 or active_count >= 5:
        return "alta"
    if wait_ratio >= 1.15 or stay_ratio >= 1.15 or active_count >= 3 or incoming_count >= 3:
        return "media"
    if wait_ratio >= 0.85 or stay_ratio >= 0.85 or active_count >= 2:
        return "baja"
    return None


def resolve_type(wait_ratio: float, stay_ratio: float, active_count: int, incoming_count: int) -> str:
    if incoming_count >= 3:
        return "fan_in"
    if active_count >= 5:
        return "overload"
    if wait_ratio >= stay_ratio:
        return "delay"
    return "critical_path"


def build_recommendation(wait_ratio: float, stay_ratio: float, active_count: int, incoming_count: int) -> str:
    recommendations: list[str] = []
    if wait_ratio >= 1.2 or active_count >= 3:
        recommendations.append("redistribuye carga o agrega responsables para bajar la cola activa")
    if stay_ratio >= 1.2:
        recommendations.append("simplifica el formulario o automatiza validaciones repetitivas")
    if incoming_count >= 2:
        recommendations.append("separa pre-validacion y aprobacion para evitar acumulacion en un solo nodo")
    if not recommendations:
        recommendations.append("monitorea este nodo y ajusta avgMinutes o la asignacion si la demanda sigue creciendo")
    return "; ".join(dict.fromkeys(recommendations))


def severity_rank(severity: str) -> int:
    normalized = severity.lower().strip()
    if normalized == "alta":
        return 0
    if normalized == "media":
        return 1
    return 2


def find_kpi_display(kpis: list[dict[str, Any]], kpi_id: str) -> str:
    for item in kpis:
        if item.get("id") == kpi_id:
            return str(item.get("displayValue") or "0 min")
    return "0 min"
