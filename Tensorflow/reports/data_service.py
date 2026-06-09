"""
data_service.py
Obtiene datos de tramites desde la API de Spring Boot (puerto 8080).
No requiere conexion directa a MongoDB.
"""

import logging

from core.api_client import api_get, refresh_token

logger = logging.getLogger(__name__)


class DataService:
    """Consulta tramites via Spring Boot REST API."""

    def __init__(self):
        self.db = None   # Compatibilidad — WorkflowMatcher recibe esto
        refresh_token()
        logger.info("DataService listo (via Spring Boot API).")

    def get_all_enriched(self) -> list[dict]:
        """Obtiene todos los trámites enriquecidos (workflowName, departmentName, etc.) desde Spring Boot."""
        try:
            data = api_get("/tramites/report-data")
            rows = data if isinstance(data, list) else []
            logger.info(f"DataService.get_all_enriched: {len(rows)} tramites.")
            return rows
        except Exception as e:
            logger.error(f"DataService.get_all_enriched error: {e}")
            return []

    def filter_rows(self, rows: list[dict], spec: dict) -> list[dict]:
        """Filtra y ordena los trámites en Python según el spec."""
        filters = spec.get("filters", {})

        result = []
        for row in rows:
            if filters.get("departmentName"):
                dept_f = filters["departmentName"].lower()
                dept_r = (row.get("departmentName") or "").lower()
                if dept_f not in dept_r and dept_r not in dept_f:
                    continue
            if filters.get("workflowName"):
                wf_f = filters["workflowName"].lower()
                wf_r = (row.get("workflowName") or "").lower()
                if wf_f not in wf_r and wf_r not in wf_f:
                    continue
            if filters.get("status"):
                if (row.get("status") or "").upper() != filters["status"].upper():
                    continue
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
        """Extrae nombres únicos de departamentos y workflows de los datos."""
        depts     = sorted({r.get("departmentName", "") for r in rows if r.get("departmentName")})
        workflows = sorted({r.get("workflowName", "") for r in rows if r.get("workflowName")})
        return {"departments": depts, "workflows": workflows, "users": []}

    def query(self, spec: dict) -> list[dict]:
        filters = spec.get("filters", {})

        params: dict = {}
        if filters.get("departmentName"):
            params["departmentName"] = filters["departmentName"]
        if filters.get("status"):
            params["status"] = filters["status"]
        if filters.get("dateFrom"):
            params["dateFrom"] = filters["dateFrom"]
        if filters.get("dateTo"):
            params["dateTo"] = filters["dateTo"]

        try:
            data = api_get("/tramites", params=params)
            rows = data if isinstance(data, list) else data.get("content", [])
            logger.info(f"DataService: {len(rows)} tramites.")
            return rows
        except Exception as e:
            logger.error(f"DataService query error: {e}")
            return []
