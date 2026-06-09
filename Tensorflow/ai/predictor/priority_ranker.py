import logging
from datetime import datetime
from core.api_client import get_mongo_db, load_workflows
from ai.predictor.time_utils import naive_dt as _naive_dt

logger = logging.getLogger(__name__)


def _level(ratio: float) -> str:
    if ratio >= 3.0: return "CRITICAL"
    if ratio >= 1.5: return "HIGH"
    if ratio >= 0.75: return "MEDIUM"
    return "LOW"


def _score_group(tramites: list, expected_h: float, now: datetime) -> list:
    """
    Calcula elapsed_h por trámite y normaliza contra el máximo del grupo
    para que el más demorado sea siempre 1.0 y los demás sean proporcionales.
    """
    meta = []
    for t in tramites:
        created = _naive_dt(t.get("createdAt", now))
        try:
            elapsed_h = max(0.0, (now - created).total_seconds() / 3600)
        except TypeError:
            elapsed_h = 0.0
        meta.append((t, elapsed_h))

    max_elapsed = max((m[1] for m in meta), default=1.0) or 1.0

    ranked = []
    for t, elapsed_h in meta:
        score = elapsed_h / max_elapsed
        ranked.append({
            "id":            str(t["_id"]),
            "code":          t.get("code", str(t["_id"])[:8]),
            "title":         t.get("title", ""),
            "workflowName":  t.get("_wf_name", ""),
            "status":        t.get("status", ""),
            "elapsedHours":  round(elapsed_h, 1),
            "expectedHours": round(expected_h, 1),
            "urgencyScore":  round(score, 3),
            "urgencyLevel":  _level(elapsed_h / max(expected_h, 0.1)),
        })
    return ranked


class PriorityRanker:
    """
    Rankea trámites activos por urgencia usando tiempo transcurrido relativo.

    Score = elapsed_h / max(elapsed_h en el grupo del mismo workflow)
    El más demorado del grupo siempre es 1.0; los demás son proporcionales.

    Level se basa en ratio = elapsed_h / expected_h:
      CRITICAL ≥ 3×  |  HIGH ≥ 1.5×  |  MEDIUM ≥ 0.75×  |  LOW < 0.75×
    """

    def __init__(self):
        self._wf_map, _ = load_workflows()
        self._db = get_mongo_db()
        logger.info("PriorityRanker listo")

    def rank(self) -> list:
        tramites = list(self._db["tramites"].find(
            {"status": {"$in": ["PENDIENTE", "EN_PROGRESO"]}},
            {"_id": 1, "code": 1, "title": 1, "workflowId": 1, "createdAt": 1, "status": 1},
        ))
        if not tramites:
            return []

        now = datetime.now()
        by_wf: dict = {}
        for t in tramites:
            by_wf.setdefault(t.get("workflowId", ""), []).append(t)

        result = []
        for wid, wf_tramites in by_wf.items():
            wf = self._wf_map.get(wid)
            if not wf:
                continue
            expected_h = wf["total_expected_min"] / 60
            for t in wf_tramites:
                t["_wf_name"] = wf["name"]
            result.extend(_score_group(wf_tramites, expected_h, now))

        result.sort(key=lambda x: x["urgencyScore"], reverse=True)
        for i, r in enumerate(result):
            r["rank"] = i + 1
        return result

    def rank_workflow(self, workflow_id: str) -> dict:
        """Rankea los trámites activos de un workflow específico."""
        if workflow_id not in self._wf_map:
            raise ValueError(f"Workflow {workflow_id} no encontrado")

        wf         = self._wf_map[workflow_id]
        expected_h = wf["total_expected_min"] / 60

        tramites_act = list(self._db["tramites"].find(
            {"workflowId": workflow_id, "status": {"$in": ["PENDIENTE", "EN_PROGRESO"]}},
            {"_id": 1, "code": 1, "title": 1, "workflowId": 1, "createdAt": 1, "status": 1},
        ))
        if not tramites_act:
            return {
                "workflowId":   workflow_id,
                "workflowName": wf["name"],
                "total":        0,
                "ranked":       [],
            }

        for t in tramites_act:
            t["_wf_name"] = wf["name"]

        ranked = _score_group(tramites_act, expected_h, datetime.now())
        ranked.sort(key=lambda x: x["urgencyScore"], reverse=True)
        for i, r in enumerate(ranked):
            r["rank"] = i + 1

        return {
            "workflowId":   workflow_id,
            "workflowName": wf["name"],
            "total":        len(ranked),
            "ranked":       ranked,
        }
