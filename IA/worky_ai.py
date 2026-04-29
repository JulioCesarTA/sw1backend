from typing import Any

from diagram_ai import HAIKU_MODEL, call_claude, parse_json_response, to_json


WORKY_SYSTEM_PROMPT = """
Eres Worky, un asistente que observa en tiempo real el canvas de un workflow.
Tu objetivo es guiar al usuario mientras construye el diagrama, usando el estado actual del canvas.
No inventes nodos ni conexiones que no existan. Basa tus respuestas en el contexto recibido.

Responde SIEMPRE con JSON puro, sin markdown, con esta estructura:
{
  "assistantName": "Worky",
  "summary": "respuesta principal corta para el usuario",
  "suggestions": [
    {
      "title": "titulo corto",
      "reason": "explicacion breve y concreta",
      "actions": []
    }
  ]
}

Reglas:
- Si el usuario envia un comando o pregunta, responde directamente en summary.
- Si no hay comando, actua de forma proactiva y revisa el canvas actual.
- Da sugerencias cortas, utiles y accionables.
- No impongas cambios. Guia al usuario.
- Si detectas un problema estructural, priorizalo.
- Puedes sugerir mejores practicas sobre inicio, fin, conexiones, decisiones, nodos aislados, formularios y responsables.
- El campo actions puede ir vacio.
- Si todo se ve razonable, igual devuelve summary con observacion util y suggestions vacias o minimas.
"""


def build_worky_prompt(body: dict[str, Any]) -> str:
    command = str(body.get("command") or "").strip()
    history = body.get("history") or []
    workflow_name = body.get("workflowName") or ""
    workflow_id = body.get("workflowId") or ""
    company_id = body.get("companyId") or ""
    departments = body.get("departments") or []
    job_roles = body.get("jobRoles") or []
    nodo = body.get("nodo") or []
    transitions = body.get("transitions") or []

    intent = (
        f"Mensaje actual del usuario para Worky: {command}\n"
        if command else
        "No hay mensaje manual del usuario. Debes observar el canvas y responder de forma proactiva.\n"
    )

    return (
        "Analiza el estado actual del workflow y responde como un copiloto en tiempo real.\n"
        "Debes mirar lo que el usuario esta haciendo en el canvas ahora mismo.\n"
        "Si el usuario pregunta algo, responde sobre ese contexto actual.\n\n"
        f"{intent}\n"
        f"Workflow ID: {workflow_id}\n"
        f"Workflow Name: {workflow_name}\n"
        f"Company ID: {company_id}\n"
        f"Nodo actuales: {to_json(nodo)}\n"
        f"Transitions actuales: {to_json(transitions)}\n"
        f"Departamentos disponibles: {to_json(departments)}\n"
        f"Roles disponibles: {to_json(job_roles)}\n"
        f"Historial reciente de chat con Worky: {to_json(history[-8:] if isinstance(history, list) else [])}\n\n"
        "Valida como minimo:\n"
        "- si existe inicio\n"
        "- si existe fin\n"
        "- si hay nodos aislados\n"
        "- si las decisiones tienen suficientes salidas\n"
        "- si hay procesos sin responsable cuando deberian tenerlo\n"
        "- si el flujo principal se entiende\n"
        "- si el formulario o forwarding parece incompleto cuando aplique\n"
    )


def analyze_worky_assistant(body: dict[str, Any]) -> dict[str, Any]:
    prompt = build_worky_prompt(body)
    response = call_claude(WORKY_SYSTEM_PROMPT, HAIKU_MODEL, 4096, [{"role": "user", "content": prompt}])
    parsed = parse_json_response(response)

    suggestions = parsed.get("suggestions", [])
    if not isinstance(suggestions, list):
        suggestions = []

    normalized_suggestions: list[dict[str, Any]] = []
    for item in suggestions[:6]:
        if not isinstance(item, dict):
            continue
        normalized_suggestions.append({
            "title": str(item.get("title", "")).strip() or "Sugerencia",
            "reason": str(item.get("reason", "")).strip(),
            "actions": item.get("actions", []) if isinstance(item.get("actions"), list) else [],
        })

    return {
        "assistantName": str(parsed.get("assistantName", "Worky")).strip() or "Worky",
        "summary": str(parsed.get("summary", "")).strip(),
        "suggestions": normalized_suggestions,
    }
