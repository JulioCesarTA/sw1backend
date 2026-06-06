"""
prompt_parser.py
Convierte un prompt de texto libre en un ReportSpec estructurado.
Sin TF: regex + fuzzy matching dinámico contra datos reales de Spring Boot.
"""
import re
import calendar
import logging
from datetime import date, timedelta
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
    "en proceso": "EN_PROCESO", "proceso": "EN_PROCESO", "en curso": "EN_PROCESO",
    "aprobado": "APROBADO", "aprobados": "APROBADO",
    "rechazado": "RECHAZADO", "rechazados": "RECHAZADO",
}

FORMAT_ALIASES = {
    "excel": "excel", "xlsx": "excel", "hoja de calculo": "excel",
    "word": "word", "doc": "word", "docx": "word",
    "pantalla": "screen", "en pantalla": "screen",
}

ORDER_MAP = {
    "fecha": "createdAt", "fecha de creacion": "createdAt",
    "departamento": "departmentName", "area": "departmentName",
    "estado": "status",
    "usuario": "userName", "solicitante": "userName",
    "workflow": "workflowName", "flujo": "workflowName",
    "codigo": "code", "id": "tramiteId",
}

GROUP_MAP = {
    "departamento": "departmentName", "area": "departmentName",
    "estado": "status",
    "usuario": "userName",
    "workflow": "workflowName", "flujo": "workflowName",
}

ALL_COLUMNS = ["code", "title", "workflowName", "departmentName", "status", "userName", "createdAt"]


def _norm(text: str) -> str:
    t = text.lower().strip()
    for a, b in [("á", "a"), ("é", "e"), ("í", "i"), ("ó", "o"), ("ú", "u"), ("ñ", "n")]:
        t = t.replace(a, b)
    return t


def _fuzzy_find(query: str, candidates: list[str]) -> str | None:
    if not candidates or not query.strip():
        return None
    q = _norm(query)
    normed = {_norm(c): c for c in candidates}
    # Exact
    if q in normed:
        return normed[q]
    # Substring
    for n, orig in normed.items():
        if n and (n in q or q in n):
            return orig
    # Fuzzy
    matches = get_close_matches(q, list(normed.keys()), n=1, cutoff=0.7)
    if matches:
        return normed[matches[0]]
    return None


class PromptParser:
    def __init__(self, departments: list[str], workflows: list[str], users: list[str]):
        self.departments = [d for d in departments if d]
        self.workflows   = [w for w in workflows if w]
        self.users       = [u for u in users if u]

    def parse(self, prompt: str) -> dict:
        t = _norm(prompt)
        filters: dict = {}

        # FORMAT (detect in prompt; default: screen)
        fmt = "screen"
        for kw, f in FORMAT_ALIASES.items():
            if kw in t:
                fmt = f
                break

        # DEPARTMENT — match against real dept names first, then after keywords
        dept = self._find_entity(t, self.departments, ["departamento", "area", "seccion", "sector"])
        if dept:
            filters["departmentName"] = dept

        # WORKFLOW
        wf = self._find_entity(t, self.workflows, ["workflow", "flujo", "proceso"])
        if wf:
            filters["workflowName"] = wf

        # USER — look after trigger words
        user = self._find_entity(
            t, self.users,
            ["usuario", "empleado", "atendido por", "responsable", "asignado a", "por"]
        )
        if user:
            filters["userName"] = user

        # STATUS — multi-word first
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

        # ORDER BY
        order_by = "createdAt"
        order_dir = "desc"
        for kw, field in ORDER_MAP.items():
            if re.search(rf'ordenad[oa]?\s+por\s+{re.escape(kw)}', t):
                order_by = field
                break
        if any(w in t for w in ["ascendente", "antiguo", "antiguos", "primero"]):
            order_dir = "asc"

        # GROUP BY
        group_by = None
        for kw, field in GROUP_MAP.items():
            if re.search(rf'agrupad[oa]?\s+(?:por\s+)?{re.escape(kw)}', t):
                group_by = field
                break

        return {
            "title":    self._build_title(prompt, filters),
            "filters":  filters,
            "groupBy":  group_by,
            "orderBy":  order_by,
            "orderDir": order_dir,
            "columns":  ALL_COLUMNS,
            "format":   fmt,
        }

    # ── helpers ────────────────────────────────────────────────────────────

    def _find_entity(self, t: str, candidates: list[str], keywords: list[str]) -> str | None:
        if not candidates:
            return None
        # Direct match: any candidate name appears verbatim in the prompt
        for c in candidates:
            if _norm(c) in t:
                return c
        # Try after trigger keywords
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

    def _parse_dates(self, t: str) -> tuple[str | None, str | None]:
        today = date.today()

        if "este mes" in t or "mes actual" in t:
            return today.replace(day=1).isoformat(), today.isoformat()

        if "este ano" in t or "este año" in t or "ano actual" in t:
            return today.replace(month=1, day=1).isoformat(), today.isoformat()

        if "mes pasado" in t or "mes anterior" in t:
            first_this = today.replace(day=1)
            last_prev  = first_this - timedelta(days=1)
            return last_prev.replace(day=1).isoformat(), last_prev.isoformat()

        # "entre enero y marzo [de 2025]"
        m = re.search(
            r'entre\s+(' + '|'.join(MONTHS_ES) + r')\s+y\s+(' + '|'.join(MONTHS_ES) + r')(?:\s+(?:de|del)\s+(\d{4}))?',
            t
        )
        if m:
            if m.group(3):
                year = int(m.group(3))
            else:
                m1_num = MONTHS_ES[m.group(1)]
                year = today.year if m1_num >= today.month else today.year - 1
            m1, m2 = MONTHS_ES[m.group(1)], MONTHS_ES[m.group(2)]
            d_from = date(year, m1, 1)
            d_to   = date(year, m2, calendar.monthrange(year, m2)[1])
            return d_from.isoformat(), d_to.isoformat()

        # "en enero [de 2025]" / "de enero ..."
        m = re.search(
            r'\b(?:en|de)\s+(' + '|'.join(MONTHS_ES) + r')(?:\s+(?:de|del)\s+(\d{4}))?',
            t
        )
        if m:
            if m.group(2):
                year = int(m.group(2))
            else:
                month_num = MONTHS_ES[m.group(1)]
                year = today.year if month_num >= today.month else today.year - 1
            month = MONTHS_ES[m.group(1)]
            d_from = date(year, month, 1)
            d_to   = date(year, month, calendar.monthrange(year, month)[1])
            return d_from.isoformat(), d_to.isoformat()

        # Numeric: "desde 01/01/2025 hasta 31/03/2025"
        m = re.search(
            r'desde\s+(\d{1,2})[/\-](\d{1,2})[/\-](\d{4})\s+hasta\s+(\d{1,2})[/\-](\d{1,2})[/\-](\d{4})',
            t
        )
        if m:
            d1 = date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
            d2 = date(int(m.group(6)), int(m.group(5)), int(m.group(4)))
            return d1.isoformat(), d2.isoformat()

        return None, None

    def _build_title(self, prompt: str, filters: dict) -> str:
        parts = ["Reporte de Trámites"]
        if filters.get("workflowName"):
            parts.append(f"— {filters['workflowName']}")
        if filters.get("departmentName"):
            parts.append(f"— {filters['departmentName']}")
        if filters.get("status"):
            parts.append(f"({filters['status'].replace('_', ' ').title()})")
        if filters.get("dateFrom") and filters.get("dateTo"):
            parts.append(f"[{filters['dateFrom']} → {filters['dateTo']}]")
        elif filters.get("dateFrom"):
            parts.append(f"[desde {filters['dateFrom']}]")
        return " ".join(parts)
