"""
api_client.py
Single source of truth for Spring Boot API access and MongoDB connections.

All TF services import from here instead of duplicating:
  - env-var constants (SPRING_API, credentials, MONGO_URI)
  - auth / token refresh logic
  - authenticated GET with automatic 401 retry
  - standard workflow data loader
  - MongoDB connection factory
"""
import logging
import os

import pymongo
import requests
from dotenv import load_dotenv

load_dotenv()

SPRING_API   = os.getenv("SPRING_API_URL",  "http://localhost:8080/api")
SPRING_EMAIL = os.getenv("SPRING_EMAIL",    "juan@gmail.com")
SPRING_PASS  = os.getenv("SPRING_PASSWORD", "julioavila")
MONGO_URI    = os.getenv("MONGODB_URI",     "")
MONGO_DB     = os.getenv("MONGODB_DB",      "workflow_db")

logger = logging.getLogger(__name__)

_token: str = ""


# ── Auth ──────────────────────────────────────────────────────────────────────

def refresh_token() -> str:
    """Login against Spring Boot and cache the JWT. Returns the token."""
    global _token
    try:
        r = requests.post(
            f"{SPRING_API}/auth/login",
            json={"email": SPRING_EMAIL, "password": SPRING_PASS},
            timeout=8,
        )
        r.raise_for_status()
        d = r.json()
        _token = d.get("accessToken") or d.get("token") or d.get("jwt") or ""
    except Exception as e:
        logger.warning(f"api_client: auth failed — {e}")
        _token = ""
    return _token


def get_headers() -> dict:
    """Return Authorization header dict, refreshing token if needed."""
    tok = _token or refresh_token()
    return {"Authorization": f"Bearer {tok}"} if tok else {}


def api_get(path: str, params: dict | None = None) -> list | dict:
    """GET {SPRING_API}{path} with automatic 401 token refresh."""
    r = requests.get(f"{SPRING_API}{path}", headers=get_headers(), params=params, timeout=15)
    if r.status_code == 401:
        refresh_token()
        r = requests.get(f"{SPRING_API}{path}", headers=get_headers(), params=params, timeout=15)
    r.raise_for_status()
    return r.json()


# ── MongoDB ───────────────────────────────────────────────────────────────────

def get_mongo_db():
    """Return a pymongo Database handle for workflow_db."""
    client = pymongo.MongoClient(MONGO_URI, serverSelectionTimeoutMS=8000)
    return client[MONGO_DB]


# ── Workflow data ─────────────────────────────────────────────────────────────

def load_workflows() -> tuple[dict, dict]:
    """
    Fetch all workflows from Spring Boot API.

    Returns:
      wf_map  : {wfId -> {name, nodos, num_nodos, total_expected_min}}
      nodo_map: {nodoId -> {avgMinutes, order, total_nodos, wfId, name}}

    Nodes of type inicio / fin / start / end are excluded from both maps.
    """
    try:
        wf_list = api_get("/workflows")
    except Exception as e:
        logger.error(f"load_workflows: {e}")
        return {}, {}

    wf_map:   dict = {}
    nodo_map: dict = {}

    for wf in (wf_list if isinstance(wf_list, list) else []):
        wid = wf.get("id")
        if not wid:
            continue
        try:
            full = api_get(f"/workflows/{wid}")
        except Exception:
            continue

        all_nodos = sorted(full.get("nodo", []), key=lambda n: n.get("order", 999))
        nodos = [
            n for n in all_nodos
            if (n.get("nodeType") or "").lower() not in ("inicio", "fin", "start", "end")
        ]

        total_min = sum(n.get("avgMinutes") or 30 for n in nodos)
        wf_map[wid] = {
            "name":               wf.get("name", ""),
            "nodos":              nodos,
            "num_nodos":          len(nodos),
            "total_expected_min": max(total_min, 1),
        }

        for i, n in enumerate(nodos):
            nodo_map[n["id"]] = {
                "avgMinutes":  n.get("avgMinutes") or 30,
                "order":       i,
                "total_nodos": len(nodos),
                "wfId":        wid,
                "name":        n.get("name", "Nodo"),
            }

    logger.info(f"load_workflows: {len(wf_map)} workflows, {len(nodo_map)} nodos")
    return wf_map, nodo_map
