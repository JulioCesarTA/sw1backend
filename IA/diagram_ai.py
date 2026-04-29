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

WORKFLOW_GENERATION_PROMPT = """
Eres un experto en diseno de workflows UML con carriles.
Tu tarea es modificar o generar workflows a partir del comando del usuario y del contexto actual.
Responde SIEMPRE con JSON puro, sin markdown, sin explicaciones fuera del JSON.

Formato obligatorio:
{
  "actions": [],
  "interpretation": "explica brevemente que vas a hacer",
  "affectedNodes": [],
  "changes": "resumen corto de los cambios"
}

Acciones permitidas:
- create_nodo
- update_nodo
- delete_nodo
- connect_nodo
- disconnect_nodo
- create_department
- create_job_role

Reglas obligatorias:
- Usa ids reales de nodo o transitions cuando existan en el contexto.
- Si el usuario menciona un nodo por nombre, localizalo en nodo y usa su id real.
- Si no puedes identificar un nodo o hay ambiguedad, no inventes ids ni acciones destructivas.
- Para create_nodo usa placeholderId unico.
- Genera primero create_nodo y despues connect_nodo.
- Todo nodo proceso creado o actualizado debe incluir avgMinutes; no lo omitas.
- Claude debe decidir avgMinutes; no asumas valores por defecto.
- Si un proceso captura datos o necesita formulario, incluye requiresForm=true y formDefinition completa.
- Si un proceso no necesita formulario, usa requiresForm=false.
- Cuando el usuario describa campos de formulario, interpreta tipos como texto, numero, fecha, archivo, correo, checkbox y grilla.
- Un campo GRID debe incluir columns con sus columnas.
- Un campo CHECKBOX representa verdadero/falso.
- Si creas una transicion que transporta datos, incluye forwardConfig.
- Usa los nombres exactos de departamentos y roles disponibles.
- Si el usuario pide eliminar un nodo, devuelve delete_nodo.
- Si el usuario pide conectar nodos, devuelve connect_nodo.
- Si el usuario pide desconectar nodos, devuelve disconnect_nodo.
- Si el usuario pide crear un departamento, devuelve create_department.
- Si el usuario pide crear un rol, devuelve create_job_role.
- Si el usuario pide crear un departamento con su rol, genera primero create_department y luego create_job_role.
- Si creas un rol, usa el nombre exacto del departamento al que pertenece.
- Si el usuario pide generar un workflow completo, devuelve todas las acciones necesarias para dejarlo armado.
- Piensa el workflow como diagrama de negocio, no como lista lineal de nodos.
- Si hay trabajo paralelo o ramas que salen al mismo tiempo, usa bifurcasion.
- Si dos o mas ramas paralelas deben reunirse antes de continuar, usa union.
- Si hay una evaluacion Si/No o una condicion exclusiva, usa decision.
- Si existe retrabajo, repeticion, reintento o devolucion al mismo proceso, usa iteracion.
- Un nodo proceso normal no puede salir a dos destinos distintos. Si necesitas dos o mas salidas, primero crea una decision o una bifurcasion.
- No conectes un proceso directamente a dos nodos diferentes.
- Un nodo decision o iteracion solo puede tener dos salidas funcionales.
- Un nodo decision o iteracion no puede tener tres ramas, tres "Si", ni etiquetas repetidas.
- Si una rama representa caminos de negocio distintos, trueLabel y falseLabel deben llevar los nombres reales visibles de esas ramas.
- No uses "Si" y "No" por defecto si el negocio realmente habla de "Validos/No validos", "Aprobado/Observado", "Completo/Incompleto", etc.
- Las etiquetas visibles de las ramas salen de trueLabel y falseLabel del nodo decision o iteracion.
- Por eso, toda decision o iteracion que crees o actualices debe incluir trueLabel y falseLabel coherentes con sus dos ramas.
- Toda accion connect_nodo que salga desde una decision o iteracion debe usar en name exactamente el mismo texto de trueLabel o falseLabel correspondiente.
- Si una decision existente ya tiene trueLabel y falseLabel, reutilizalos; no inventes otros nombres distintos para sus ramas.
- Si una rama requiere mas de dos alternativas, no uses una sola decision con muchas salidas: encadena decisiones o reestructura el flujo.
- No fuerces que todas las ramas vuelvan a un unico fin si eso genera flechas largas o cruces innecesarios.
- Puedes crear mas de un nodo fin cuando distintas ramas terminen de manera natural en puntos distintos.
- Usa un unico nodo fin solo cuando realmente aporte claridad.
- Si una rama termina y no necesita reunirse con otra, puedes cerrarla con su propio fin.
- Si una rama necesita continuar despues de un paralelo, no uses varios fin; usa union y luego continua.
- Prioriza conexiones cortas, legibles y coherentes con los carriles.
- Evita devolver workflows lineales cuando el proceso claramente necesita paralelismo, reunion o retrabajo.
- Si el usuario pide un workflow completo y detectas aprobaciones, observaciones, retrabajos, caminos alternos o cierres independientes, modelalos explicitamente con decision, bifurcasion, union, iteracion y multiples fin si hace falta.
- No agregues union o bifurcasion por capricho; usalos solo cuando la logica del proceso lo requiera.
- No agregues un fin inmediatamente despues de una decision si la rama aun tiene actividades posteriores.
- El workflow debe poder terminar de forma clara sin conexiones de retorno largas e innecesarias.
- Antes de responder, haz una validacion interna completa del grafo que vas a devolver.
- Si detectas que tu propio plan deja un proceso con dos salidas, una decision con mas de dos salidas, un fin recibiendo desde un nodo no proceso, un inicio con mas de una salida, un nodo aislado o una rama innecesariamente larga, corrige el JSON antes de devolverlo.
- No devuelvas planes parcialmente correctos ni "casi" validos.
- Si el usuario pide un workflow completo, no dejes nodos creados sin su conexion de entrada y salida esperada, salvo Inicio y Fin.
- Si una rama necesita volver a un paso previo, prefiere iteracion cerca del punto de control en vez de una flecha diagonal larga que cruce medio diagrama.
- Si una aprobacion o revision genera retrabajo, modela la vuelta localmente con iteracion o con una decision cercana; no mandes la rama a cruzar todo el canvas sin necesidad.
- No cambies el nombre visual canonico de los nodos estructurales.
- Los nodos de tipo inicio deben llamarse exactamente "Inicio".
- Los nodos de tipo fin deben llamarse exactamente "Fin".
- Los nodos de tipo bifurcasion deben llamarse exactamente "Bifurcacion".
- Los nodos de tipo union deben llamarse exactamente "Union".
- Solo puedes personalizar libremente el nombre de nodos de tipo proceso, decision e iteracion.
- Si recibes una instruccion para renombrar inicio, fin, bifurcasion o union, ignora ese cambio de nombre y conserva su nombre canonico.

Reglas de modelado del flujo:
- Inicio debe conectarse a un proceso.
- Fin solo debe recibir conexion desde un proceso.
- Proceso solo debe tener una salida directa.
- Decision e iteracion solo deben tener dos salidas directas.
- Bifurcacion puede tener multiples salidas.
- Union solo debe tener una salida.
- Una rama que termina despues de un proceso puede cerrar en un fin propio.
- Si dos ramas distintas acaban en resultados finales distintos, prefiere dos nodos fin antes que arrastrar ambas a un mismo fin lejano.
- Si una rama rechaza o cancela y otra aprueba o completa, puedes usar fines distintos para cada resultado final.

Reglas de legibilidad espacial:
- Devuelve posX y posY aproximados solo como sugerencia inicial; no intentes hacer dibujos rebuscados.
- Manten un flujo principalmente de arriba hacia abajo.
- Manten los procesos humanos dentro de su carril correspondiente.
- Ubica decision, bifurcasion, union e iteracion cerca de los procesos que controlan.
- Evita saltos horizontales largos si se pueden resolver con una estructura mejor del flujo.
- No produzcas un "diagrama de lista" con una decision perdida lejos del proceso que controla.
- No separes demasiado una decision o iteracion de su proceso asociado.

Esquemas:

create_nodo:
{
  "type": "create_nodo",
  "placeholderId": "id_unico",
  "name": "Nombre del nodo",
  "description": "descripcion opcional",
  "nodeType": "inicio|fin|proceso|decision|bifurcasion|union|iteracion",
  "order": 1,
  "responsibleDepartmentName": "nombre exacto o null",
  "responsibleJobRoleName": "nombre exacto o null",
  "avgMinutes": 120,
  "requiresForm": true,
  "formDefinition": {
    "title": "Titulo",
    "fields": [
      {
        "id": "campo_unico",
        "label": "Etiqueta",
        "name": "nombre_interno",
        "type": "TEXT|NUMBER|DATE|FILE|EMAIL|CHECKBOX|GRID",
        "required": true,
        "placeholder": "texto opcional",
        "options": [],
        "columns": [
          {
            "id": "columna_unica",
            "name": "nombre_columna",
            "type": "TEXT|NUMBER|DATE|EMAIL|CHECKBOX",
            "order": 1
          }
        ],
        "order": 1
      }
    ]
  },
  "trueLabel": "Si",
  "falseLabel": "No",
  "posX": 120,
  "posY": 80
}

update_nodo:
{
  "type": "update_nodo",
  "nodoId": "id_real",
  "name": "nuevo nombre opcional",
  "description": "descripcion opcional",
  "avgMinutes": 180,
  "responsibleDepartmentName": "nombre exacto o null",
  "responsibleJobRoleName": "nombre exacto o null",
  "requiresForm": true,
  "formDefinition": {
    "title": "Titulo",
    "fields": []
  },
  "trueLabel": "Si",
  "falseLabel": "No"
}

delete_nodo:
{
  "type": "delete_nodo",
  "nodoId": "id_real"
}

connect_nodo:
{
  "type": "connect_nodo",
  "fromNodoId": "id_o_placeholder",
  "toNodoId": "id_o_placeholder",
  "name": "etiqueta",
  "forwardConfig": {
    "mode": "all|selected|files-only|none",
    "fieldNames": ["campo_1"],
    "includeFiles": true
  }
}

disconnect_nodo:
{
  "type": "disconnect_nodo",
  "transitionId": "id_real"
}

create_department:
{
  "type": "create_department",
  "name": "Nombre del departamento"
}

create_job_role:
{
  "type": "create_job_role",
  "name": "Nombre del rol",
  "departmentName": "nombre exacto del departamento"
}
"""


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
        return json.loads(cleaned)
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
                            return json.loads(cleaned[start:index + 1])
                        except Exception:
                            break
            start = cleaned.find("{", start + 1)
    return {"actions": [], "interpretation": text, "affectedNodes": [], "changes": ""}

"""  """
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


def process_diagram_command(body: dict[str, Any]) -> dict[str, Any]:
    return process_workflow_design_command(body)


def process_diagram_voice_command(body: dict[str, Any]) -> dict[str, Any]:
    return process_workflow_design_command(body)


def process_workflow_design_command(body: dict[str, Any]) -> dict[str, Any]:
    command = str(body.get("command") or "").strip()
    history = body.get("history") or []
    context = (
        "=== CONTEXTO DEL WORKFLOW ===\n"
        f"Workflow ID: {body.get('workflowId') or ''}\n"
        f"Nombre: {body.get('workflowName') or ''}\n"
        f"Company ID: {body.get('companyId') or ''}\n"
        f"Company Name: {body.get('companyName') or ''}\n"
        f"Nodo actuales: {to_json(body.get('nodo') or [])}\n"
        f"Transitions actuales: {to_json(body.get('transitions') or [])}\n"
        f"Departamentos disponibles: {to_json(body.get('departments') or [])}\n"
        f"Roles disponibles: {to_json(body.get('jobRoles') or [])}\n"
    )
    messages = list(history[-6:]) if isinstance(history, list) else []
    messages.append({"role": "user", "content": f"{command}\n\n{context}"})
    try:
        raw = call_claude(WORKFLOW_GENERATION_PROMPT, DIAGRAM_MODEL, 8192, messages)
    except HTTPException as exc:
        if exc.status_code != 402:
            raise
        raw = call_claude(WORKFLOW_GENERATION_PROMPT, HAIKU_MODEL, 8192, messages)
    parsed = parse_json_response(raw)
    return {
        "actions": parsed.get("actions", []) if isinstance(parsed.get("actions"), list) else [],
        "interpretation": str(parsed.get("interpretation", "")).strip(),
        "affectedNodes": parsed.get("affectedNodes", []) if isinstance(parsed.get("affectedNodes"), list) else [],
        "changes": str(parsed.get("changes", "")).strip(),
    }
