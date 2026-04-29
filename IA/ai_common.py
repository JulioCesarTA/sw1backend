import json
import os
from pathlib import Path
from typing import Any

import requests
from fastapi import HTTPException
from pymongo import MongoClient


CLAUDE_URL = "https://api.anthropic.com/v1/messages"
DIAGRAM_MODEL = "claude-sonnet-4-6"
HAIKU_MODEL = "claude-haiku-4-5-20251001"


def load_dotenv_file() -> None:
    env_path = Path(__file__).resolve().parent.parent / ".env"
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def get_db():
    uri = os.getenv("MONGODB_URI", "")
    if not uri:
        raise HTTPException(status_code=503, detail="MONGODB_URI no configurada")
    client = MongoClient(uri)
    default_db = client.get_default_database()
    db_name = default_db.name if default_db is not None else "workflow_db"
    return client[db_name]


def to_json(obj: Any) -> str:
    try:
        return json.dumps(obj, ensure_ascii=False, default=str)
    except Exception:
        return "[]"


def parse_json_response(text: str) -> dict[str, Any]:
    cleaned = text.replace("```json", "").replace("```", "").strip()
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        start = cleaned.find("{")
        while start != -1:
            depth = 0
            in_string = False
            escape = False
            for index in range(start, len(cleaned)):
                char = cleaned[index]
                if in_string:
                    if escape:
                        escape = False
                    elif char == "\\":
                        escape = True
                    elif char == '"':
                        in_string = False
                    continue
                if char == '"':
                    in_string = True
                elif char == "{":
                    depth += 1
                elif char == "}":
                    depth -= 1
                    if depth == 0:
                        try:
                            parsed = json.loads(cleaned[start:index + 1])
                            return parsed if isinstance(parsed, dict) else {}
                        except Exception:
                            break
            start = cleaned.find("{", start + 1)
    return {}


def call_claude(system_prompt: str, model: str, max_tokens: int, messages: list[dict[str, Any]]) -> str:
    api_key = os.getenv("CLAUDE_API_KEY", "")
    if not api_key:
        raise HTTPException(status_code=503, detail="API key de Claude no configurada")
    try:
        response = requests.post(
            CLAUDE_URL,
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json={
                "model": model,
                "max_tokens": max_tokens,
                "system": system_prompt,
                "messages": messages,
            },
            timeout=120,
        )
        if not response.ok:
            body = response.text
            if response.status_code == 402 or "credit" in body.lower():
                raise HTTPException(status_code=402, detail="Sin creditos en la API de Claude")
            raise HTTPException(status_code=502, detail=f"Error de la API de Claude: {body}")
        parsed = response.json()
        content = parsed.get("content") or []
        if not content:
            raise HTTPException(status_code=502, detail="Respuesta vacia de Claude")
        return str(content[0].get("text", ""))
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Error llamando a Claude: {exc}") from exc
