"""
prompt_parser.py
Convierte un prompt en un ReportSpec estructurado.

El usuario siempre especifica:
  - mes + año  (ej. "abril 2025")
  - formato    (pantalla / excel / word)
  - agrupado por X
  - ordenado asc o desc
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
    "fecha": "createdAt",
    "departamento": "departmentName", "area": "departmentName",
    "estado": "status",
    "workflow": "workflowName", "flujo": "workflowName",
    "codigo": "code",
}

GROUP_MAP = {
    "departamento": "departmentName", "area": "departmentName",
    "estado": "status",
    "workflow": "workflowName", "flujo": "workflowName",
}

ALL_COLUMNS = ["code", "title", "workflowName", "departmentName", "status", "userName", "createdAt"]


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
        filters: dict = {}

        # FORMAT
        fmt = "screen"
        for kw, f in FORMAT_ALIASES.items():
            if kw in t:
                fmt = f
                break

        # DEPARTMENT
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

        # DATES — mes + año
        date_from, date_to = self._parse_month_year(t)
        if date_from:
            filters["dateFrom"] = date_from
        if date_to:
            filters["dateTo"] = date_to

        # ORDER BY
        order_by  = "createdAt"
        order_dir = "desc"
        for kw, field in ORDER_MAP.items():
            if re.search(rf'(?:ordenad[oa]?\s+por\s+|por\s+){re.escape(kw)}', t):
                order_by = field
                break
        if any(w in t for w in ["ascendente", "ascendentemente", "antiguos", "antiguo", " asc"]):
            order_dir = "asc"
        elif any(w in t for w in ["descendente", "descendentemente", "reciente", "recientes", " desc"]):
            order_dir = "desc"

        # GROUP BY
        group_by = None
        for kw, field in GROUP_MAP.items():
            if re.search(rf'agrupad[oa]?\s+(?:por\s+)?{re.escape(kw)}', t):
                group_by = field
                break

        return {
            "title":    self._build_title(filters),
            "filters":  filters,
            "groupBy":  group_by,
            "orderBy":  order_by,
            "orderDir": order_dir,
            "columns":  ALL_COLUMNS,
            "format":   fmt,
        }

    def _find_entity(self, t: str, candidates: list[str], keywords: list[str]) -> str | None:
        if not candidates:
            return None
        for c in candidates:
            if _norm(c) in t:
                return c
        for kw in keywords:
            m = re.search(
                rf'{re.escape(kw)}\s+(?:de\s+|del\s+|la\s+|el\s+)?([a-z0-9áéíóúñ\s]+?)(?=\s+(?:en|de|del|entre|desde|hasta|que|y|ordenad|agrupad|\Z)|$)',
                t
            )
            if m:
                found = _fuzzy_find(m.group(1).strip(), candidates)
                if found:
                    return found
        return None

    def _parse_month_year(self, t: str) -> tuple[str | None, str | None]:
        """Detecta 'mes [año]' — si no hay año usa el año actual."""
        months_pattern = '|'.join(MONTHS_ES)
        # Con año: "febrero 2025" / "febrero del 2025"
        m = re.search(rf'\b({months_pattern})\s+(?:de(?:l)?\s+)?(\d{{4}})\b', t)
        if m:
            month = MONTHS_ES[m.group(1)]
            year  = int(m.group(2))
            last_day = calendar.monthrange(year, month)[1]
            return date(year, month, 1).isoformat(), date(year, month, last_day).isoformat()
        # Sin año: "del mes de febrero" / "de febrero" / solo "febrero"
        m = re.search(rf'(?:del?\s+mes\s+de\s+|de\s+|\b)({months_pattern})\b', t)
        if m:
            month = MONTHS_ES[m.group(1)]
            year  = date.today().year
            last_day = calendar.monthrange(year, month)[1]
            return date(year, month, 1).isoformat(), date(year, month, last_day).isoformat()
        return None, None

    def _build_title(self, filters: dict) -> str:
        parts = ["Reporte de Trámites"]
        if filters.get("workflowName"):
            parts.append(f"— {filters['workflowName']}")
        if filters.get("departmentName"):
            parts.append(f"— {filters['departmentName']}")
        if filters.get("status"):
            parts.append(f"({filters['status'].replace('_', ' ').title()})")
        if filters.get("dateFrom") and filters.get("dateTo"):
            parts.append(f"[{filters['dateFrom']} → {filters['dateTo']}]")
        return " ".join(parts)
