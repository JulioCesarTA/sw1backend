"""
prompt_parser.py  v2
Convierte un prompt en lenguaje natural a un ReportSpec estructurado.

Intents soportados:
  tramite_list    → lista de trámites con filtros opcionales
  department_flow → conteo agrupado por departamento
  avg_time        → tiempo promedio de resolución por dpto/workflow
"""
import re
import calendar
import logging
from datetime import date
from difflib import get_close_matches

logger = logging.getLogger(__name__)

MONTHS_ES = {
    "enero": 1, "febrero": 2, "marzo": 3, "abril": 4,
    "mayo": 5, "junio": 6, "julio": 7, "agosto": 8,
    "septiembre": 9, "octubre": 10, "noviembre": 11, "diciembre": 12,
}

STATUS_ALIASES = {
    "completado": "COMPLETADO", "completados": "COMPLETADO",
    "completada": "COMPLETADO", "completadas": "COMPLETADO",
    "terminado": "COMPLETADO", "finalizado": "COMPLETADO",
    "pendiente": "PENDIENTE", "pendientes": "PENDIENTE",
    "en progreso": "EN_PROGRESO", "en proceso": "EN_PROGRESO",
    "aprobado": "APROBADO", "aprobados": "APROBADO",
    "rechazado": "RECHAZADO", "rechazados": "RECHAZADO",
}

FORMAT_ALIASES = {
    "excel": "excel", "xlsx": "excel",
    "word": "word", "doc": "word", "docx": "word",
    "pantalla": "screen", "en pantalla": "screen",
}

ORDER_MAP = {
    "fecha":        "createdAt",
    "departamento": "departmentName",
    "area":         "departmentName",
    "estado":       "status",
    "workflow":     "workflowName",
    "flujo":        "total",
    "cantidad":     "total",
    "codigo":       "code",
    "tiempo":       "avgMinutes",
    "promedio":     "avgMinutes",
}

GROUP_MAP = {
    "departamento": "departmentName",
    "area":         "departmentName",
    "estado":       "status",
    "workflow":     "workflowName",
    "flujo":        "workflowName",
}

ALL_COLUMNS       = ["code", "title", "workflowName", "departmentName", "status", "userName", "createdAt"]
DEPT_FLOW_COLUMNS = ["departmentName", "total"]
AVG_TIME_COLUMNS  = ["departmentName", "workflowName", "avgMinutes", "count"]


def _norm(text: str) -> str:
    t = text.lower().strip()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    return t


def _fuzzy_find(query: str, candidates: list[str]) -> str | None:
    if not candidates or not query.strip():
        return None
    q = _norm(query)
    normed = {_norm(c): c for c in candidates}
    if q in normed:
        return normed[q]
    for n, orig in normed.items():
        if n and (n in q or q in n):
            return orig
    matches = get_close_matches(q, list(normed.keys()), n=1, cutoff=0.7)
    return normed[matches[0]] if matches else None


class PromptParser:
    def __init__(self, departments: list[str], workflows: list[str], users: list[str]):
        self.departments = [d for d in departments if d]
        self.workflows   = [w for w in workflows if w]

    def parse(self, prompt: str) -> dict:
        t = _norm(prompt)

        intent = self._detect_intent(t)

        filters: dict = {}

        # FORMAT
        fmt = "screen"
        for kw, f in FORMAT_ALIASES.items():
            if kw in t:
                fmt = f
                break

        # TRAMITE CODE  (TRM00004, TRM-001, etc.)
        code_match = re.search(r'\btrm[-]?\d+\b', t)
        if code_match:
            raw = code_match.group(0).replace("-", "").upper()
            # Normalise to TRMxxxxx format
            filters["code"] = raw

        # DEPARTMENT  (skip if just asking for dept flow with no dept name)
        if intent != "department_flow" or filters.get("code"):
            dept = self._find_entity(t, self.departments, ["departamento", "area", "seccion"])
            if dept:
                filters["departmentName"] = dept
        elif intent == "department_flow":
            dept = self._find_entity(t, self.departments, ["departamento", "area", "seccion"])
            if dept:
                filters["departmentName"] = dept

        # WORKFLOW
        wf = self._find_entity(t, self.workflows, ["workflow", "flujo", "proceso"])
        if wf:
            filters["workflowName"] = wf

        # STATUS
        for kw, val in sorted(STATUS_ALIASES.items(), key=lambda x: -len(x[0])):
            if kw in t:
                filters["status"] = val
                break

        # DATES
        date_from, date_to = self._parse_dates(t)
        if date_from:
            filters["dateFrom"] = date_from
        if date_to:
            filters["dateTo"] = date_to

        # ORDER DIR
        order_dir = "desc"
        asc_kws  = ["ascendente", "ascendentemente", "menor a mayor", "de menor a mayor", " asc"]
        desc_kws = ["descendente", "descendentemente", "mayor a menor", "de mayor a menor", " desc", "mayor flujo", "mayor cantidad"]
        if any(w in t for w in asc_kws):
            order_dir = "asc"
        elif any(w in t for w in desc_kws):
            order_dir = "desc"

        # ORDER BY
        order_by = "total" if intent == "department_flow" else ("avgMinutes" if intent == "avg_time" else "createdAt")
        for kw, field in ORDER_MAP.items():
            if re.search(rf'(?:ordenad[oa]?\s+por\s+|por\s+){re.escape(kw)}', t):
                order_by = field
                break

        # GROUP BY
        group_by = None
        for kw, field in GROUP_MAP.items():
            if re.search(rf'agrupad[oa]?\s+(?:por\s+)?{re.escape(kw)}', t):
                group_by = field
                break

        if intent == "department_flow":
            title   = "Departamentos con Mayor Flujo"
            columns = DEPT_FLOW_COLUMNS
        elif intent == "avg_time":
            title   = self._build_avg_time_title(filters)
            columns = AVG_TIME_COLUMNS
        else:
            title   = self._build_title(filters)
            columns = ALL_COLUMNS

        return {
            "title":    title,
            "filters":  filters,
            "groupBy":  group_by,
            "orderBy":  order_by,
            "orderDir": order_dir,
            "columns":  columns,
            "format":   fmt,
            "intent":   intent,
        }

    # ─── Intent detection ────────────────────────────────────────────────────

    def _detect_intent(self, t: str) -> str:
        dept_flow_patterns = [
            r'departamento[s]?\s+(?:con\s+)?(?:mayor|mas|m[aá]s|menor|menos)\s+(?:flujo|tramite|actividad|cantidad)',
            r'(?:mayor|menor|mas|menos)\s+flujo\s+(?:de\s+)?(?:tramite|departamento)',
            r'cuantos?\s+tramites?\s+(?:tiene|tienen|hay\s+en)\s+(?:cada\s+)?departamento',
            r'flujo\s+(?:por|de|en)\s+departamento',
            r'departamento[s]?\s+(?:ordenad[oa])',
        ]
        for pat in dept_flow_patterns:
            if re.search(pat, t):
                return "department_flow"

        avg_time_patterns = [
            r'tiempo\s+promedio',
            r'promedio\s+(?:de\s+)?(?:tiempo|duraci[oó]n|resoluci[oó]n)',
            r'cu[aá]nto\s+(?:tarda?|demora?|toma)',
            r'velocidad\s+(?:de\s+)?resoluci[oó]n',
        ]
        for pat in avg_time_patterns:
            if re.search(pat, t):
                return "avg_time"

        return "tramite_list"

    # ─── Entity matching ─────────────────────────────────────────────────────

    def _find_entity(self, t: str, candidates: list[str], keywords: list[str]) -> str | None:
        if not candidates:
            return None
        for c in candidates:
            if _norm(c) in t:
                return c
        for kw in keywords:
            m = re.search(
                rf'{re.escape(kw)}\s+(?:de\s+|del\s+|la\s+|el\s+)?([a-z0-9aeious\s]+?)(?=\s+(?:en|de|del|entre|desde|hasta|que|y|ordenad|agrupad|\Z)|$)',
                t
            )
            if m:
                found = _fuzzy_find(m.group(1).strip(), candidates)
                if found:
                    return found
        return None

    # ─── Date parsing ─────────────────────────────────────────────────────────

    def _parse_dates(self, t: str) -> tuple[str | None, str | None]:
        months_pattern = '|'.join(MONTHS_ES.keys())

        # Range with two different months: "del 10 de junio al 15 de julio [del 2026]"
        range_diff = re.search(
            rf'(?:del?\s+)?(\d{{1,2}})\s+de\s+({months_pattern})(?:\s+(?:del?\s+)?(\d{{4}}))?\s+'
            rf'(?:al?|hasta(?:\s+el)?)\s+(?:el\s+)?(\d{{1,2}})\s+de\s+({months_pattern})(?:\s+(?:del?\s+)?(\d{{4}}))?',
            t
        )
        if range_diff:
            d1, m1, y1, d2, m2, y2 = range_diff.groups()
            year = int(y1 or y2 or date.today().year)
            return (
                date(year, MONTHS_ES[m1], int(d1)).isoformat(),
                date(int(y2 or year), MONTHS_ES[m2], int(d2)).isoformat(),
            )

        # Range within same month: "del 10 al 15 de junio [del 2026]"
        range_same = re.search(
            rf'(?:del?\s+)?(\d{{1,2}})\s+(?:de\s+)?(?:al?\s+)?(\d{{1,2}})\s+de\s+({months_pattern})(?:\s+(?:del?\s+)?(\d{{4}}))?',
            t
        )
        if range_same:
            d1, d2, m, y = range_same.groups()
            year = int(y or date.today().year)
            month = MONTHS_ES[m]
            return date(year, month, int(d1)).isoformat(), date(year, month, int(d2)).isoformat()

        # Range: "del 10 de junio hasta el 15 [de junio]" (second day, same month inferred)
        range_partial = re.search(
            rf'(?:del?\s+)?(\d{{1,2}})\s+de\s+({months_pattern})(?:\s+(?:del?\s+)?(\d{{4}}))?\s+'
            rf'(?:al?|hasta(?:\s+el)?)\s+(?:el\s+)?(\d{{1,2}})',
            t
        )
        if range_partial:
            d1, m, y, d2 = range_partial.groups()
            year = int(y or date.today().year)
            month = MONTHS_ES[m]
            return date(year, month, int(d1)).isoformat(), date(year, month, int(d2)).isoformat()

        # Specific day: "el 10 de junio del 2026" / "del 10 de junio"
        day_m = re.search(
            rf'(?:el\s+|del?\s+)?(\d{{1,2}})\s+de\s+({months_pattern})(?:\s+(?:del?\s+|de\s+)?(\d{{4}}))?',
            t
        )
        if day_m:
            d, m, y = day_m.groups()
            year = int(y or date.today().year)
            month = MONTHS_ES[m]
            specific = date(year, month, int(d)).isoformat()
            return specific, specific

        # Month + year: "junio 2026"
        my = re.search(rf'\b({months_pattern})\s+(?:de(?:l)?\s+)?(\d{{4}})\b', t)
        if my:
            month = MONTHS_ES[my.group(1)]
            year  = int(my.group(2))
            last  = calendar.monthrange(year, month)[1]
            return date(year, month, 1).isoformat(), date(year, month, last).isoformat()

        # Just month: "de junio" / "el mes de junio"
        mo = re.search(rf'(?:del?\s+mes\s+de\s+|de\s+|\b)({months_pattern})\b', t)
        if mo:
            month = MONTHS_ES[mo.group(1)]
            year  = date.today().year
            last  = calendar.monthrange(year, month)[1]
            return date(year, month, 1).isoformat(), date(year, month, last).isoformat()

        return None, None

    # ─── Title builders ───────────────────────────────────────────────────────

    def _build_title(self, filters: dict) -> str:
        if filters.get("code"):
            return f"Trámite {filters['code']}"
        parts = ["Reporte de Trámites"]
        if filters.get("workflowName"):
            parts.append(f"— {filters['workflowName']}")
        if filters.get("departmentName"):
            parts.append(f"— {filters['departmentName']}")
        if filters.get("status"):
            parts.append(f"({filters['status'].replace('_', ' ').title()})")
        if filters.get("dateFrom") and filters.get("dateTo"):
            if filters["dateFrom"] == filters["dateTo"]:
                parts.append(f"[{filters['dateFrom']}]")
            else:
                parts.append(f"[{filters['dateFrom']} → {filters['dateTo']}]")
        return " ".join(parts)

    def _build_avg_time_title(self, filters: dict) -> str:
        parts = ["Tiempo Promedio de Resolución"]
        if filters.get("departmentName"):
            parts.append(f"— {filters['departmentName']}")
        if filters.get("workflowName"):
            parts.append(f"— {filters['workflowName']}")
        return " ".join(parts)
