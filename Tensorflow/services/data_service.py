"""
data_service.py
Obtiene datos de tramites desde la API de Spring Boot (puerto 8080).
No requiere conexion directa a MongoDB.
"""

import logging
import os
import requests

logger = logging.getLogger(__name__)

SPRING_API = os.getenv("SPRING_API_URL", "http://localhost:8080/api")
SPRING_EMAIL = os.getenv("SPRING_EMAIL", "juan@gmail.com")
SPRING_PASSWORD = os.getenv("SPRING_PASSWORD", "julioavila")

_token: str = ""


def _get_token() -> str:
    global _token
    try:
        r = requests.post(f"{SPRING_API}/auth/login",
                          json={"email": SPRING_EMAIL, "password": SPRING_PASSWORD},
                          timeout=8)
        r.raise_for_status()
        data = r.json()
        _token = data.get("token") or data.get("accessToken") or data.get("jwt") or ""
        logger.info("DataService: token JWT obtenido.")
    except Exception as e:
        logger.warning(f"DataService: no se pudo obtener token: {e}")
        _token = ""
    return _token


def _headers() -> dict:
    tok = _token or _get_token()
    return {"Authorization": f"Bearer {tok}"} if tok else {}


class DataService:
    """Consulta tramites via Spring Boot REST API."""

    def __init__(self):
        self.db = None   # Compatibilidad — WorkflowMatcher recibe esto
        _get_token()
        logger.info("DataService listo (via Spring Boot API).")

    def get_all_enriched(self) -> list[dict]:
        """Obtiene todos los trámites enriquecidos (workflowName, departmentName, etc.) desde Spring Boot."""
        try:
            r = requests.get(f"{SPRING_API}/tramites/report-data", headers=_headers(), timeout=15)
            if r.status_code == 401:
                _get_token()
                r = requests.get(f"{SPRING_API}/tramites/report-data", headers=_headers(), timeout=15)
            r.raise_for_status()
            data = r.json()
            rows = data if isinstance(data, list) else []
            logger.info(f"DataService.get_all_enriched: {len(rows)} tramites.")
            return rows
        except Exception as e:
            logger.error(f"DataService.get_all_enriched error: {e}")
            return []

    def filter_rows(self, rows: list[dict], spec: dict) -> list[dict]:
        """Filtra y ordena los trámites en Python según el spec."""
        from datetime import date as _date
        filters = spec.get("filters", {})

        # Filter
        result = []
        for row in rows:
            # departmentName — case-insensitive contains
            if filters.get("departmentName"):
                dept_f = filters["departmentName"].lower()
                dept_r = (row.get("departmentName") or "").lower()
                if dept_f not in dept_r and dept_r not in dept_f:
                    continue
            # workflowName
            if filters.get("workflowName"):
                wf_f = filters["workflowName"].lower()
                wf_r = (row.get("workflowName") or "").lower()
                if wf_f not in wf_r and wf_r not in wf_f:
                    continue
            # userName
            if filters.get("userName"):
                u_f = filters["userName"].lower()
                u_r = (row.get("userName") or "").lower()
                if u_f not in u_r and u_r not in u_f:
                    continue
            # status — exact
            if filters.get("status"):
                if (row.get("status") or "").upper() != filters["status"].upper():
                    continue
            # dateFrom / dateTo — compare against createdAt (ISO string)
            created = row.get("createdAt", "")
            if filters.get("dateFrom") and created:
                if created[:10] < filters["dateFrom"]:
                    continue
            if filters.get("dateTo") and created:
                if created[:10] > filters["dateTo"]:
                    continue
            result.append(row)

        # Sort
        order_by  = spec.get("orderBy", "createdAt")
        order_dir = spec.get("orderDir", "desc")
        reverse = order_dir == "desc"
        result.sort(key=lambda r: (r.get(order_by) or ""), reverse=reverse)

        return result

    def extract_context(self, rows: list[dict]) -> dict:
        """Extrae nombres únicos de departamentos, workflows y usuarios de los datos."""
        depts     = sorted({r.get("departmentName", "") for r in rows if r.get("departmentName")})
        workflows = sorted({r.get("workflowName", "") for r in rows if r.get("workflowName")})
        users     = sorted({r.get("userName", "") for r in rows if r.get("userName")})
        return {"departments": depts, "workflows": workflows, "users": users}

    def query(self, spec: dict) -> list[dict]:
        filters = spec.get("filters", {})

        params: dict = {}
        if filters.get("departmentName"):
            params["departmentName"] = filters["departmentName"]
        if filters.get("status"):
            params["status"] = filters["status"]
        if filters.get("userName"):
            params["userName"] = filters["userName"]
        if filters.get("dateFrom"):
            params["dateFrom"] = filters["dateFrom"]
        if filters.get("dateTo"):
            params["dateTo"] = filters["dateTo"]

        try:
            r = requests.get(f"{SPRING_API}/tramites",
                             params=params, headers=_headers(), timeout=10)
            if r.status_code == 401:
                _get_token()
                r = requests.get(f"{SPRING_API}/tramites",
                                 params=params, headers=_headers(), timeout=10)
            r.raise_for_status()
            data = r.json()
            # Normalizar a lista
            rows = data if isinstance(data, list) else data.get("content", [])
            logger.info(f"DataService: {len(rows)} tramites.")
            return rows
        except Exception as e:
            logger.error(f"DataService query error: {e}")
            return []
