"""
entity_extractor.py
Convierte la salida del NER + texto crudo en un ReportSpec normalizado.
Usa dateparser para normalizar fechas en español.
"""

import re
import logging
import dateparser
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

# Meses en español → número
MONTHS = {
    "enero": 1, "febrero": 2, "marzo": 3, "abril": 4,
    "mayo": 5, "junio": 6, "julio": 7, "agosto": 8,
    "septiembre": 9, "octubre": 10, "noviembre": 11, "diciembre": 12,
}

STATUS_MAP = {
    "completado": "COMPLETADO", "completados": "COMPLETADO",
    "pendiente": "PENDIENTE",   "pendientes": "PENDIENTE",
    "proceso": "EN_PROCESO",    "proceso": "EN_PROCESO",
    "aprobado": "APROBADO",     "aprobados": "APROBADO",
    "rechazado": "RECHAZADO",   "rechazados": "RECHAZADO",
    "en proceso": "EN_PROCESO",
}

FORMAT_KEYWORDS = {
    "word": "word", "documento": "word", "doc": "word",
    "excel": "excel", "hoja": "excel", "tabla": "excel",
    "pantalla": "screen", "ver": "screen",
}

DEFAULT_COLUMNS = ["tramiteId", "workflowName", "departmentName",
                   "status", "userName", "createdAt"]


def extract_entities(nlp_result: dict, requested_format: str = "screen") -> dict:
    """
    Toma la salida de NLPService.analyze() y devuelve un dict con:
    intent, filters, columns, orderBy, title, format
    """
    text       = nlp_result["raw_text"].lower()
    intent     = nlp_result["intent"]
    ner_tokens = nlp_result["ner_tokens"]  # list[(token, tag)]

    filters: dict = {}

    # ------------------------------------------------------------------ #
    # Departamento — tokens B-DEPT / I-DEPT del NER
    # ------------------------------------------------------------------ #
    dept_tokens = _collect_bio(ner_tokens, "DEPT")
    if dept_tokens:
        filters["departmentName"] = " ".join(dept_tokens).title()

    # ------------------------------------------------------------------ #
    # Usuario — tokens B-USER / I-USER
    # ------------------------------------------------------------------ #
    user_tokens = _collect_bio(ner_tokens, "USER")
    if user_tokens:
        filters["userName"] = " ".join(user_tokens).title()

    # ------------------------------------------------------------------ #
    # Estado — tokens B-STATUS / I-STATUS
    # ------------------------------------------------------------------ #
    status_tokens = _collect_bio(ner_tokens, "STATUS")
    if status_tokens:
        raw_status = " ".join(status_tokens).lower()
        filters["status"] = STATUS_MAP.get(raw_status, raw_status.upper())

    # Fallback: buscar palabras de estado en el texto directamente
    if "status" not in filters:
        for kw, val in STATUS_MAP.items():
            if re.search(r'\b' + re.escape(kw) + r'\b', text):
                filters["status"] = val
                break

    # ------------------------------------------------------------------ #
    # Fechas — tokens B-DATE / I-DATE → dateparser
    # ------------------------------------------------------------------ #
    date_tokens = _collect_bio(ner_tokens, "DATE")

    # Intentar patrones explícitos primero
    date_from, date_to = _extract_date_range(text)
    if date_from:
        filters["dateFrom"] = date_from
    if date_to:
        filters["dateTo"] = date_to

    # Si NER encontró tokens de fecha pero aún no hay fechas, parsear
    if date_tokens and "dateFrom" not in filters:
        parsed = _parse_single_date(date_tokens[0])
        if parsed:
            filters["dateFrom"] = parsed
            # asumir fin del mes como dateTo
            filters["dateTo"] = _end_of_month(parsed)

    # ------------------------------------------------------------------ #
    # Título dinámico
    # ------------------------------------------------------------------ #
    title = _build_title(intent, filters)

    return {
        "intent":  intent,
        "filters": filters,
        "columns": DEFAULT_COLUMNS,
        "orderBy": "createdAt",
        "orderDir": "desc",
        "title":   title,
        "format":  requested_format,
    }


# ------------------------------------------------------------------ #
# Helpers
# ------------------------------------------------------------------ #

def _collect_bio(ner_tokens: list[tuple[str, str]], entity: str) -> list[str]:
    """Reúne tokens B-X e I-X consecutivos de la entidad `entity`."""
    result = []
    collecting = False
    for token, tag in ner_tokens:
        if tag == f"B-{entity}":
            collecting = True
            result.append(token)
        elif tag == f"I-{entity}" and collecting:
            result.append(token)
        else:
            if collecting and result:
                break
            collecting = False
    return result


def _extract_date_range(text: str) -> tuple[str | None, str | None]:
    """
    Detecta patrones como:
      - "entre enero y marzo"
      - "del 1 al 31 de enero"
      - "en enero de 2025"
      - "este mes", "el mes pasado", "este año"
    Devuelve (dateFrom ISO, dateTo ISO) o (None, None).
    """
    now = datetime.now()

    # "este mes"
    if re.search(r'\beste\s+mes\b', text):
        first = now.replace(day=1)
        return first.strftime("%Y-%m-%d"), now.strftime("%Y-%m-%d")

    # "el mes pasado" / "mes pasado"
    if re.search(r'\bmes\s+pasado\b', text):
        first_this = now.replace(day=1)
        last_prev  = first_this - timedelta(days=1)
        first_prev = last_prev.replace(day=1)
        return first_prev.strftime("%Y-%m-%d"), last_prev.strftime("%Y-%m-%d")

    # "este año"
    if re.search(r'\beste\s+año\b', text):
        return f"{now.year}-01-01", now.strftime("%Y-%m-%d")

    # "primer trimestre" / "segundo trimestre" ...
    tri_map = {"primer": (1, 3), "segundo": (4, 6), "tercer": (7, 9), "cuarto": (10, 12)}
    for word, (m_start, m_end) in tri_map.items():
        if re.search(rf'\b{word}\s+trimestre\b', text):
            year = now.year
            m = re.search(r'\b(20\d\d)\b', text)
            if m:
                year = int(m.group(1))
            return f"{year}-{m_start:02d}-01", f"{year}-{m_end:02d}-{_last_day(year, m_end):02d}"

    # "entre <mes> y <mes>" con año opcional
    m = re.search(
        r'entre\s+(' + '|'.join(MONTHS) + r')\s+y\s+(' + '|'.join(MONTHS) + r')(?:\s+(?:de\s+)?(20\d\d))?',
        text
    )
    if m:
        year   = int(m.group(3)) if m.group(3) else now.year
        m_from = MONTHS[m.group(1)]
        m_to   = MONTHS[m.group(2)]
        return (f"{year}-{m_from:02d}-01",
                f"{year}-{m_to:02d}-{_last_day(year, m_to):02d}")

    # "en <mes> de <año>" o "del mes de <mes>"
    m = re.search(r'\b(?:en|de)\s+(' + '|'.join(MONTHS) + r')(?:\s+(?:de\s+)?(20\d\d))?', text)
    if m:
        year   = int(m.group(2)) if m.group(2) else now.year
        month  = MONTHS[m.group(1)]
        return (f"{year}-{month:02d}-01",
                f"{year}-{month:02d}-{_last_day(year, month):02d}")

    return None, None


def _parse_single_date(token: str) -> str | None:
    try:
        parsed = dateparser.parse(token, languages=["es"])
        if parsed:
            return parsed.strftime("%Y-%m-%d")
    except Exception:
        pass
    return None


def _end_of_month(date_iso: str) -> str:
    d = datetime.fromisoformat(date_iso)
    return d.replace(day=_last_day(d.year, d.month)).strftime("%Y-%m-%d")


def _last_day(year: int, month: int) -> int:
    import calendar
    return calendar.monthrange(year, month)[1]


def _build_title(intent: str, filters: dict) -> str:
    parts = ["Reporte"]
    if filters.get("departmentName"):
        parts.append(f"— {filters['departmentName']}")
    if filters.get("userName"):
        parts.append(f"— {filters['userName']}")
    if filters.get("status"):
        parts.append(f"({filters['status'].capitalize()})")
    if filters.get("dateFrom") and filters.get("dateTo"):
        parts.append(f"[{filters['dateFrom']} → {filters['dateTo']}]")
    elif filters.get("dateFrom"):
        parts.append(f"desde {filters['dateFrom']}")
    if len(parts) == 1:
        parts.append("General")
    return " ".join(parts)
