import json
import os
import re
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from typing import Any

import requests
from fastapi import FastAPI, HTTPException
from pymongo import MongoClient


CLAUDE_URL = "https://api.anthropic.com/v1/messages"
DIAGRAM_MODEL = "claude-sonnet-4-6"
HAIKU_MODEL = "claude-haiku-4-5-20251001"


DIAGRAM_SYSTEM_PROMPT = """
Eres un asistente experto en diseÃ±o de flujos de trabajo (workflows).
El usuario te darÃ¡ instrucciones en espaÃ±ol para modificar el diagrama.
Responde SIEMPRE con un JSON vÃ¡lido con la siguiente estructura:
{
  "actions": [ { "type": "create_stage|update_stage|delete_stage|connect_stages|disconnect_stages|show_diagram", ...campos } ],
  "interpretation": "texto explicando quÃ© se harÃ¡",
  "affectedNodes": ["id1","id2"],
  "changes": "resumen de cambios"
}
Tipos de acciÃ³n:
- create_stage: { type, name, description, nodeType, order }
- update_stage: { type, stageId, name?, description?, slaHours? }
- delete_stage: { type, stageId }
- connect_stages: { type, fromStageId, toStageId, name? }
- disconnect_stages: { type, transitionId }
- show_diagram: { type } â€” solo muestra el estado actual
"""

BOTTLENECK_SYSTEM_PROMPT = """
Eres un experto en optimizaciÃ³n de procesos y anÃ¡lisis de flujos de trabajo.
RecibirÃ¡s datos de un workflow con mÃ©tricas de nodos (indegree, outdegree, SLA, etc).
Identifica cuellos de botella y oportunidades de mejora.
Debes responder de forma accionable: indica el nodo exacto, por qu?? se congestiona y una soluci??n coherente con el editor de workflows.
Prioriza recomendaciones como:
- insertar una decision/condicional para separar casos simples vs complejos
- usar bifurcacion y union cuando exista trabajo paralelo real
- redistribuir carga por roles del mismo departamento
- derivar casos por monto, riesgo, complejidad, tipo de tramite o prioridad
- reservar el rol principal para casos complejos y enviar casos simples a un rol secundario/auxiliar
Responde SIEMPRE con JSON:
{
  "bottlenecks": [
    {
      "stageId": "id",
      "stageName": "nombre",
      "type": "sla_violation|fan_in|role_overload|single_path|parallelization|critical_path",
      "severity": "high|medium|low",
      "description": "explicaciÃ³n",
      "recommendation": "soluciÃ³n propuesta"
    }
  ],
  "summary": "resumen ejecutivo",
  "parallelizationOpportunities": [ { "stageIds": ["id1","id2"], "reason": "..." } ]
}
"""

WORKFLOW_GENERATION_PROMPT = """
Eres un experto en diseÃ±o de workflows UML con carriles (swimlanes).
Responde SIEMPRE con un objeto JSON puro, sin bloques markdown, sin texto extra.

ESTRUCTURA DE RESPUESTA:
{
  "actions": [...],
  "interpretation": "",
  "affectedNodes": [],
  "changes": "resumen"
}

Si la instruccion del usuario es operativa, devuelve "interpretation": "".
NO devuelvas texto fuera del JSON.

ACCIONES PERMITIDAS:

create_stage:
{
  "type": "create_stage",
  "placeholderId": "id_unico_sin_espacios",
  "name": "Nombre del nodo",
  "description": "descripcion opcional",
  "nodeType": "start|end|process|decision|fork|join|loop",
  "order": <numero entero, incremental>,
  "responsibleDepartmentName": "nombre exacto del departamento o null",
  "responsibleJobRoleName": "nombre exacto del rol o null",
  "requiresForm": true|false,
  "formDefinition": {
    "title": "Titulo del formulario",
    "fields": [
      {
        "id": "campo_unico",
        "label": "Etiqueta visible",
        "name": "nombre_interno",
        "type": "TEXT|NUMBER|DATE|FILE",
        "required": true|false,
        "placeholder": "texto opcional",
        "options": [],
        "order": 1
      }
    ]
  },
  "trueLabel": "etiqueta si es decision o loop (decision: Si/Aprobado/Cumple, loop: Repetir)",
  "falseLabel": "etiqueta si es decision o loop (decision: No/Rechazado/No cumple, loop: Salir)",
  "posX": <numero pixeles>,
  "posY": <numero pixeles>
}

connect_stages:
{
  "type": "connect_stages",
  "fromStageId": "placeholderId o id existente",
  "toStageId": "placeholderId o id existente",
  "name": "etiqueta de la flecha (Si/No para decisiones, vacio para el resto)",
  "forwardConfig": {
    "mode": "all|selected|files-only|none",
    "fieldNames": ["campo_1", "campo_2"],
    "includeFiles": true|false
  }
}

update_stage: { "type":"update_stage", "stageId":"id", "name":?, "description":?, "slaHours":?, "responsibleDepartmentName":?, "responsibleJobRoleName":?, "requiresForm":?, "formDefinition":?, "trueLabel":?, "falseLabel":? }
delete_stage:  { "type":"delete_stage", "stageId":"id" }

TIPOS DE NODO (nodeType):
- "start"    : nodo de inicio; sin actor; exactamente 1 por workflow
- "end"      : nodo de fin; puede haber varios
- "process"  : paso manual realizado por un actor (asigna departamento y rol)
- "decision" : bifurcacion condicional; EXACTAMENTE 2 conexiones salientes: una con name=trueLabel, otra con name=falseLabel
- "fork"     : bifurcacion paralela real; EXACTAMENTE 1 entrada y 2 o mas salidas hacia procesos/ramas distintas que se ejecutan simultaneamente; sin actor
- "join"     : union/sincronizacion paralela real; 2 o mas entradas desde ramas distintas y EXACTAMENTE 1 salida hacia un unico proceso posterior; sin actor; DEBE esperar a TODAS las ramas del fork correspondiente
- "loop"     : nodo de iteracion/retrabajo; EXACTAMENTE 2 conexiones salientes: una con name=trueLabel para repetir/reintentar/reinspeccionar y otra con name=falseLabel para salir del ciclo y continuar el flujo

REGLAS CRITICAS:
1. Cada create_stage DEBE tener un placeholderId unico, sin espacios ni caracteres especiales.
2. En connect_stages usa SIEMPRE los placeholderIds definidos en create_stage.
3. Genera PRIMERO todos los create_stage y DESPUES todos los connect_stages.
4. Para flujo paralelo: predecessor -> fork -> rama1 y predecessor -> fork -> rama2 NO es valido como doble entrada al fork; la forma correcta es predecessor -> fork, luego fork -> rama1 y fork -> rama2; despues rama1 -> join y rama2 -> join; finalmente join -> sucesor.
4.1. Un fork SOLO puede recibir una conexion entrante.
4.2. Un fork DEBE repartir el flujo a 2 o mas ramas salientes diferentes.
4.3. Un join DEBE recibir 2 o mas conexiones entrantes desde ramas paralelas.
4.4. Un join SOLO puede tener una conexion saliente, hacia un unico proceso o nodo sucesor.
4.5. NO conectes directamente varios nodos hacia un fork.
4.6. NO hagas que un join distribuya el flujo a varios nodos; join une, no bifurca.
4.7. Si no existe paralelismo real, NO uses fork ni join.
4.8. Si quieres separar caminos alternativos usa decision, no fork.
4.9. Todo fork debe tener su join de cierre correspondiente, salvo que el usuario pida explicitamente ramas paralelas abiertas.
5. Para decision: decision -> nodo_si (name=trueLabel), decision -> nodo_no (name=falseLabel).
5.1. Para loop: etapa_evaluada_o_retrabajo -> loop; loop -> etapa_a_repetir (name=trueLabel, normalmente "Repetir"); loop -> siguiente_etapa_fuera_del_ciclo (name=falseLabel, normalmente "Salir").
5.2. Usa loop cuando el proceso pueda repetirse hasta cumplir una condicion: observaciones, rechazo tecnico, reinspeccion, retrabajo, correccion, volver a revisar, volver a intentar o repetir una etapa.
5.3. NO uses decision para modelar retrabajo repetitivo. Usa decision para ramas de negocio alternativas y loop para volver a una etapa previa o de correccion.
5.4. En nodos loop usa por defecto trueLabel="Repetir" y falseLabel="Salir", salvo que el usuario pida etiquetas equivalentes mas especificas.
6. Un nodo process NO debe quedar colgado: si una decision o loop apunta a un process, ese process debe continuar hacia otro nodo, loop, join o end.
6.1. Un process normal NO debe usarse como nodo de union informal de varias ramas. Si dos o mas ramas deben converger, usa join. Si una rama debe volver a una etapa previa para rehacerla, usa loop.
6.2. Evita conectar dos procesos distintos hacia un mismo process, salvo en un caso de retorno de loop claramente intencional. La convergencia general de varias ramas debe resolverse con join, no con process.
6.3. Un process normalmente debe tener una sola salida. Si necesitas varias salidas, usa decision o fork segun corresponda.
6.4. Despues de una decision, cada rama debe terminar en end o reincorporarse correctamente al flujo mediante loop o join; NO dejes ramas muertas.
7. Usa responsibleDepartmentName y responsibleJobRoleName con el nombre EXACTO del contexto.
8. NUNCA dejes un nodo sin conexion de entrada (excepto start) ni sin conexion de salida (excepto end).
9. Si un proceso debe capturar datos del usuario, crea requiresForm=true y agrega formDefinition con fields utiles y names unicos.
10. Si un proceso posterior necesita solo algunos datos del formulario anterior, usa connect_stages.forwardConfig.mode="selected" con fieldNames exactos del formulario origen.
11. Si el siguiente proceso necesita todos los datos del anterior, usa connect_stages.forwardConfig.mode="all".
12. Si solo deben pasar archivos, usa connect_stages.forwardConfig.mode="files-only" e includeFiles=true.
13. Si la instruccion pide eliminar, conectar, renombrar o insertar un join/union entre nodos existentes, prioriza update/delete/connect sobre recrear nodos innecesariamente.
14. Si el usuario menciona "formulario", "campo", "dato", "archivo", "adjunto", "llenar", "capturar", "recibir datos" o "pasar datos", tu respuesta es INVALIDA si no incluyes requiresForm/formDefinition o forwardConfig cuando corresponda.
15. Si creas un process que captura datos, incluye SIEMPRE:
   - "requiresForm": true
   - "formDefinition.title"
   - "formDefinition.fields" con al menos label, name, type, required y order
16. Si una conexion debe transportar datos especificos, incluye SIEMPRE "forwardConfig" en connect_stages. No lo omitas.
17. DIFERENCIA OBLIGATORIA:
   - Para borrar un nodo usa SOLO delete_stage con stageId de una etapa existente.
   - Para borrar una conexion/flecha/transicion usa SOLO disconnect_stages con transitionId de una transicion existente.
   - Si el usuario dice "elimina la conexion", "borra la flecha", "quita la transicion", JAMAS respondas con delete_stage.
18. Si el usuario dice crear, eliminar, conectar, actualizar, renombrar, mover o insertar, responde con acciones ejecutables y "interpretation": "".
19. NO uses frases como "Analizando el workflow" ni expliques conexiones antes del JSON.
20. Si el usuario pide "elimina un nodo" o "borra una etapa", elimina SOLO ese nodo con delete_stage.
21. Al eliminar un nodo, NO inventes conexiones nuevas, NO reconectes automaticamente y NO preserves continuidad salvo que el usuario lo pida de forma explicita.
22. Al eliminar un nodo, asume que el sistema ya eliminara automaticamente sus formularios, conexiones entrantes y conexiones salientes. Tu trabajo es devolver delete_stage del nodo pedido, nada mas.
23. SOLO agrega connect_stages despues de un delete_stage si el usuario pide explicitamente "reconecta", "une", "mantén el flujo", "conecta directamente", "bypass" o una instruccion equivalente.

EJEMPLO OBLIGATORIO DE FORMULARIO:
Si el usuario pide "crear recepcion con formulario de nombre del cliente y direccion", el create_stage correcto debe verse asi:
{
  "type":"create_stage",
  "placeholderId":"recepcion_form",
  "name":"Recepcion de Solicitud",
  "nodeType":"process",
  "responsibleDepartmentName":"Atencion al Cliente",
  "responsibleJobRoleName":"Recepcionista",
  "requiresForm":true,
  "formDefinition":{
    "title":"Solicitud",
    "fields":[
      {"id":"nombre_cliente","label":"Nombre del cliente","name":"nombre_cliente","type":"TEXT","required":true,"order":1},
      {"id":"direccion","label":"Direccion","name":"direccion","type":"TEXT","required":true,"order":2}
    ]
  }
}

EJEMPLO OBLIGATORIO DE PASO DE DATOS:
Si el usuario pide "pasar solo nombre_cliente y direccion a la siguiente etapa", la connect_stages correcta debe verse asi:
{
  "type":"connect_stages",
  "fromStageId":"recepcion_form",
  "toStageId":"validacion_docs",
  "name":"",
  "forwardConfig":{
    "mode":"selected",
    "fieldNames":["nombre_cliente","direccion"],
    "includeFiles":false
  }
}

EJEMPLO OBLIGATORIO DE ELIMINAR CONEXION:
Si el usuario pide "elimina la conexion entre Recepcion de Solicitud y Validacion de Documentos", la accion correcta es:
{
  "type":"disconnect_stages",
  "transitionId":"id_real_de_la_transicion"
}
NO uses delete_stage para esto.

LAYOUT SWIMLANE (posX/posY):
- Asigna una columna por departamento. Separa columnas 300px (col0=50, col1=350, col2=650, col3=950).
- Apila nodos del mismo departamento verticalmente, separados 180px por fila de flujo (row0=50, row1=230, row2=410, ...).
- Nodos fork/join: colocalos en la columna del nodo que los antecede o sucede; si cruzan carriles, usa la columna central entre las ramas.
- El nodo start va en row=50. El nodo end va en la fila mas baja del flujo.
- Si hay ramas paralelas, ponlas en la misma fila pero en columnas diferentes.
"""

WORKY_ASSISTANT_PROMPT = """
Eres "Worky", un asistente experto en diagramas de workflow UML con swimlanes.
Analiza el workflow actual y responde SIEMPRE con JSON puro, sin markdown.

ESTRUCTURA:
{
  "assistantName": "Worky",
  "summary": "resumen corto",
  "suggestions": [
    {
      "id": "missing_start|disconnected_processes|missing_end|decision_missing_branches|missing_join_after_fork|unclear_names|no_closure|next_step_help|too_linear",
      "message": "mensaje exacto o muy cercano al catalogo",
      "reason": "explicacion breve",
      "priority": "high|medium|low",
      "actions": [
        { "type": "create_stage|update_stage|delete_stage|connect_stages|disconnect_stages", "...": "..." }
      ]
    }
  ]
}

CATALOGO DE MENSAJES ESPERADOS:
- "Recuerda que el nodo Inicio debe estar conectado con un proceso. Quieres que te agregue ese proceso?"
- "Recuerda poner el nodo Fin para concluir tu workflow. Quieres que lo agregue?"
- "Tu flujo no tiene un nodo de inicio. Â¿Deseas que lo agregue automÃ¡ticamente?"
- "DetectÃ© procesos sin conexiÃ³n. Â¿Quieres que los conecte para mantener un flujo continuo?"
- "Tu diagrama no tiene un nodo final. Â¿Deseas agregar uno automÃ¡ticamente?"
- "Recuerda que una decisiÃ³n debe tener al menos dos salidas (SÃ­/No). Â¿Quieres que las cree?"
- "Has creado una bifurcaciÃ³n pero no una uniÃ³n. Â¿Deseas que agregue la uniÃ³n para cerrar el flujo paralelo?"
- "Algunos procesos no tienen nombres claros. Â¿Deseas que sugiera nombres mÃ¡s descriptivos?"
- "Tu workflow no tiene un cierre definido. Â¿Deseas agregar un nodo de fin?"
- "Â¿Necesitas ayuda? Puedo sugerirte el siguiente paso en tu diagrama."
- "Este flujo tiene muchos pasos lineales. Â¿Quieres que sugiera una decisiÃ³n para hacerlo mÃ¡s dinÃ¡mico?"

REGLAS:
- Comportate como un asistente contextual del editor: observa lo que hay en el canvas y ayuda al usuario a entender el siguiente paso logico del software.
- Devuelve solo sugerencias RELEVANTES al diagrama actual.
- No dupliques sugerencias equivalentes.
- Prioriza problemas estructurales graves: inicio, fin, nodos desconectados, decisiones incompletas, fork sin join.
- Tambien puedes devolver recordatorios pedagogicos en tiempo real aunque el diagrama aun no este "mal", por ejemplo: Inicio sin proceso siguiente, falta de Fin, ramas incompletas o proceso aislado.
- Si propones acciones, deben ser ejecutables por el editor actual.
- Acciones permitidas:
  create_stage:
  {
    "type":"create_stage",
    "placeholderId":"id_unico",
    "name":"Nombre",
    "description":"descripcion opcional",
    "nodeType":"start|end|process|decision|fork|join|loop",
    "order":1,
    "responsibleDepartmentName":"nombre exacto o null",
    "responsibleJobRoleName":"nombre exacto o null",
    "trueLabel":"Si",
    "falseLabel":"No",
    "posX":50,
    "posY":50
  }
  connect_stages: { "type":"connect_stages", "fromStageId":"id_o_placeholder", "toStageId":"id_o_placeholder", "name":"Si|No|..." }
  update_stage: { "type":"update_stage", "stageId":"id", "name":"...", "description":"..." }
  delete_stage: { "type":"delete_stage", "stageId":"id" }
  disconnect_stages: { "type":"disconnect_stages", "transitionId":"id" }
- Si falta un start, crea exactamente uno y conectalo al primer nodo logico.
- Si existe un start pero no esta conectado a ningun proceso, sugiere conectar el start al primer process disponible o crear un process inicial.
- Si falta un end/cierre, crea uno y conecta los nodos hoja.
- Si una decision no tiene dos salidas, crea salidas "Si" y "No". Si no existen destinos, crea nodos end o process simples.
- Si detectas retrabajo, reinspeccion, correccion o repeticion hasta cumplir una condicion, sugiere un nodo loop en vez de una decision comun. El loop debe tener 2 salidas: "Repetir" hacia la etapa que se rehace o reevalua y "Salir" hacia la siguiente etapa fuera del ciclo.
- Usa fork/join SOLO si hay trabajo en paralelo real.
- En fork debe entrar un solo nodo y salir hacia varias ramas distintas.
- En join deben entrar varias ramas distintas y salir hacia un solo nodo posterior.
- No conectes varias ramas directamente a un process comun para "juntarlas"; usa join.
- No dejes un process creado desde una rama de decision sin continuidad; si notificas, corriges o revisas algo, luego conecta ese process al siguiente paso logico, a un loop o a end.
- Si hay fork sin join, crea un join y conecta las ramas activas hacia ese join.
- Si los nombres son vagos ("Etapa 1", "Proceso", "Nodo"), usa update_stage para proponer nombres mas claros.
- Si el flujo es muy lineal, puedes sugerir insertar un nodo decision, pero solo si tiene sentido.
"""


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default)


def load_dotenv_file() -> None:
    env_path = Path(__file__).resolve().parent.parent / "backend" / ".env"
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


load_dotenv_file()


def get_db():
    uri = env("MONGODB_URI")
    if not uri:
        raise HTTPException(status_code=503, detail="MONGODB_URI no configurada")
    client = MongoClient(uri)
    db_name = client.get_default_database().name if client.get_default_database() else "workflow_db"
    return client[db_name]


def to_json(obj: Any) -> str:
    try:
        return json.dumps(obj, ensure_ascii=False, default=str)
    except Exception:
        return "[]"


def parse_json_response(text: str) -> dict[str, Any]:
    def fallback() -> dict[str, Any]:
        return {
            "interpretation": text,
            "actions": [],
            "affectedNodes": [],
            "changes": "",
        }

    def extract_first_json_block(raw: str) -> str | None:
        start = raw.find("{")
        while start != -1:
            depth = 0
            in_string = False
            escape = False
            for index in range(start, len(raw)):
                char = raw[index]
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
                        return raw[start:index + 1]
            start = raw.find("{", start + 1)
        return None

    try:
        cleaned = text.replace("```json", "").replace("```", "").strip()
        return json.loads(cleaned)
    except Exception:
        try:
            cleaned = text.replace("```json", "").replace("```", "").strip()
            extracted = extract_first_json_block(cleaned)
            if extracted:
                return json.loads(extracted)
        except Exception:
            pass
        return fallback()


def normalize_diagram_response(payload: dict[str, Any]) -> dict[str, Any]:
    actions = payload.get("actions")
    if not isinstance(actions, list):
        actions = []

    affected_nodes = payload.get("affectedNodes")
    if not isinstance(affected_nodes, list):
        affected_nodes = []

    changes = payload.get("changes")
    if not isinstance(changes, str):
        changes = ""

    interpretation = payload.get("interpretation")
    if not isinstance(interpretation, str):
        interpretation = ""

    if actions:
        interpretation = ""

    return {
        "actions": actions,
        "interpretation": interpretation.strip(),
        "affectedNodes": affected_nodes,
        "changes": changes.strip(),
    }


def call_claude(system_prompt: str, model: str, max_tokens: int, messages: list[dict[str, Any]]) -> str:
    api_key = env("CLAUDE_API_KEY")
    if not api_key:
        raise HTTPException(status_code=503, detail="API key de Claude no configurada")

    payload = {
        "model": model,
        "max_tokens": max_tokens,
        "system": system_prompt,
        "messages": messages,
    }
    try:
        response = requests.post(
            CLAUDE_URL,
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json=payload,
            timeout=120,
        )
        body = response.text
        if not response.ok:
            if response.status_code == 402 or "credit" in body.lower():
                raise HTTPException(status_code=402, detail="Sin créditos en la API de Claude")
            raise HTTPException(status_code=502, detail=f"Error de la API de Claude: {body}")

        parsed = response.json()
        content = parsed.get("content") or []
        if not content:
            raise HTTPException(status_code=502, detail="Respuesta vacía de Claude")
        return content[0].get("text", "")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Error llamando a Claude: {exc}") from exc


def round_hours(value: float) -> float:
    return round(value * 10.0) / 10.0


def worky_action(*key_values: Any) -> dict[str, Any]:
    action: dict[str, Any] = {}
    for index in range(0, len(key_values) - 1, 2):
        key = str(key_values[index]) if key_values[index] is not None else None
        if key is None:
            continue
        action[key] = key_values[index + 1]
    return action


def normalize_worky_suggestions(parsed: dict[str, Any]) -> dict[str, Any]:
    raw_suggestions = parsed.get("suggestions") or []
    seen: set[str] = set()
    suggestions: list[dict[str, Any]] = []

    for suggestion in raw_suggestions:
        if not isinstance(suggestion, dict):
            continue
        suggestion_id = str(suggestion.get("id", "")).strip()
        if not suggestion_id or suggestion_id in seen:
            continue
        seen.add(suggestion_id)
        suggestions.append(suggestion)

    return {
        "assistantName": str(parsed.get("assistantName", "Worky")),
        "summary": str(parsed.get("summary", "Worky revisó tu diagrama.")),
        "suggestions": suggestions,
    }


def owner_label(department_name: str, role_name: str) -> str:
    if department_name and role_name:
        return f"el departamento {department_name} ({role_name})"
    if department_name:
        return f"el departamento {department_name}"
    if role_name:
        return f"el rol {role_name}"
    return ""


def build_fan_in_recommendation(stage: dict[str, Any], department_name: str, role_name: str) -> str:
    stage_name = str(stage.get("name", "este nodo"))
    owner = owner_label(department_name, role_name)
    if owner:
        return (
            f'Agrega una decision antes de "{stage_name}" para separar casos simples y complejos. '
            f"Los casos de menor monto, riesgo o complejidad pueden derivarse a un rol secundario dentro de "
            f"{owner}, mientras que los complejos quedan en el responsable principal. "
            f"Si hay tareas independientes, considera una bifurcacion para repartir validaciones previas y luego una union."
        )
    return (
        f'Agrega una decision antes de "{stage_name}" para separar casos simples y complejos, '
        f"o crea una prevalidacion para filtrar tramites antes de llegar a este cuello de botella."
    )


def build_critical_path_recommendation(stage: dict[str, Any], department_name: str, role_name: str) -> str:
    stage_name = str(stage.get("name", "este nodo"))
    owner = owner_label(department_name, role_name)
    if owner:
        return (
            f'Todos los tramites pasan por "{stage_name}". Conviene insertar una decision previa que derive por monto, riesgo, prioridad o complejidad. '
            f"Por ejemplo, los casos simples pueden ir a un rol secundario o auxiliar en {owner} y los casos complejos quedar en el rol principal. "
            f"Si parte del trabajo puede hacerse en paralelo, abre una bifurcacion y reconcilia despues con una union."
        )
    return (
        f'Todos los tramites pasan por "{stage_name}". Inserta una decision previa para derivar casos simples y complejos por reglas de negocio, '
        f"y usa paralelizacion solo si existen tareas independientes."
    )


def build_role_overload_recommendation(stage: dict[str, Any], department_name: str, role_name: str) -> str:
    stage_name = str(stage.get("name", "este nodo"))
    if department_name and role_name:
        return (
            f'El rol "{role_name}" en {department_name} concentra demasiado trabajo en "{stage_name}". '
            f"Considera repartir la carga con una decision previa: casos de bajo monto, bajo riesgo o baja complejidad hacia un rol secundario/auxiliar, "
            f"y casos complejos hacia el rol principal."
        )
    if department_name:
        return (
            f"Este nodo sobrecarga al departamento {department_name}. Considera separar el flujo con una decision por monto, complejidad o prioridad "
            f"y asignar roles distintos para absorber la demanda."
        )
    return (
        "Este nodo concentra demasiada carga en un mismo responsable. Considera separar casos simples y complejos con una decision previa "
        "o agregar un segundo rol para descongestionar."
    )


def add_bottleneck(bottlenecks: list[dict[str, Any]], added: set[str], bottleneck: dict[str, Any]) -> None:
    key = f'{bottleneck.get("stageId", "")}|{bottleneck.get("type", "")}'
    if key not in added:
        added.add(key)
        bottlenecks.append(bottleneck)


def collect_paths(
    current: str,
    adjacency: dict[str, list[str]],
    visited: set[str],
    path: list[str],
    all_paths: list[list[str]],
    leaves: list[str],
) -> None:
    if current in visited:
        return
    visited.add(current)
    path.append(current)

    next_nodes = adjacency.get(current, [])
    if not next_nodes or current in leaves:
        all_paths.append(list(path))
        return

    for next_node in next_nodes:
        collect_paths(next_node, adjacency, set(visited), list(path), all_paths, leaves)


def compute_average_hours_by_stage(workflow_id: str, stages: list[dict[str, Any]]) -> dict[str, float]:
    if not workflow_id:
        return {}

    stage_ids = {str(stage.get("id", "")) for stage in stages if str(stage.get("id", ""))}
    if not stage_ids:
        return {}

    db = get_db()
    procedures = list(db.procedures.find({"workflowId": workflow_id}, {"_id": 1}))
    durations_by_stage: dict[str, list[float]] = defaultdict(list)

    for procedure in procedures:
        procedure_id = str(procedure.get("_id", ""))
        if not procedure_id:
            continue
        histories = db.procedure_history.find({"procedureId": procedure_id}).sort("changedAt", 1)
        for history in histories:
            stage_id = str(history.get("toStageId", ""))
            if not stage_id or stage_id not in stage_ids:
                continue
            duration_hours = history.get("durationInStage") or 0
            try:
                duration_hours = float(duration_hours)
            except Exception:
                duration_hours = 0
            if duration_hours > 0:
                durations_by_stage[stage_id].append(duration_hours)

    averages: dict[str, float] = {}
    for stage_id, values in durations_by_stage.items():
        averages[stage_id] = round_hours(sum(values) / len(values))
    return averages


def build_local_bottleneck_analysis(
    workflow_id: str,
    stages: list[dict[str, Any]],
    transitions: list[dict[str, Any]],
    average_hours_by_stage: dict[str, float],
) -> dict[str, Any]:
    stages_by_id: dict[str, dict[str, Any]] = {}
    indegree: dict[str, int] = defaultdict(int)
    outdegree: dict[str, int] = defaultdict(int)
    adjacency: dict[str, list[str]] = {}

    for stage in stages:
        stage_id = str(stage.get("id", ""))
        if not stage_id:
            continue
        stages_by_id[stage_id] = stage
        adjacency.setdefault(stage_id, [])

    for transition in transitions:
        from_id = str(transition.get("fromStageId", ""))
        to_id = str(transition.get("toStageId", ""))
        if from_id:
            outdegree[from_id] += 1
        if to_id:
            indegree[to_id] += 1
        if from_id and to_id:
            adjacency.setdefault(from_id, []).append(to_id)

    roots = [
        str(stage.get("id", ""))
        for stage in stages
        if str(stage.get("id", "")) and indegree.get(str(stage.get("id", "")), 0) == 0
    ]
    leaves = [
        str(stage.get("id", ""))
        for stage in stages
        if str(stage.get("id", "")) and outdegree.get(str(stage.get("id", "")), 0) == 0
    ]

    all_paths: list[list[str]] = []
    for root in roots:
        collect_paths(root, adjacency, set(), [], all_paths, leaves)

    path_coverage: dict[str, int] = defaultdict(int)
    for path in all_paths:
        for stage_id in dict.fromkeys(path):
            path_coverage[stage_id] += 1

    enriched_stages: list[dict[str, Any]] = []
    bottlenecks: list[dict[str, Any]] = []
    added: set[str] = set()

    for stage in stages:
        stage_id = str(stage.get("id", ""))
        node_type = str(stage.get("nodeType", ""))
        department_name = str(stage.get("responsibleDepartmentName", ""))
        role_name = str(stage.get("responsibleJobRoleName", ""))
        average_hours = average_hours_by_stage.get(stage_id, float(stage.get("slaHours", 24)))
        indegree_value = indegree.get(stage_id, 0)
        outdegree_value = outdegree.get(stage_id, 0)
        coverage = path_coverage.get(stage_id, 0)

        enriched = deepcopy(stage)
        enriched["averageHours"] = round_hours(average_hours)
        enriched["expectedHours"] = float(stage.get("slaHours", 24))
        enriched["indegree"] = indegree_value
        enriched["outdegree"] = outdegree_value
        enriched["pathCoverage"] = coverage
        enriched["pathCoverageRatio"] = 0 if not all_paths else coverage / len(all_paths)
        enriched_stages.append(enriched)

        if node_type.lower() in {"process", "decision", "join"}:
            if average_hours >= 24 and indegree_value >= 2:
                add_bottleneck(
                    bottlenecks,
                    added,
                    {
                        "stageId": stage_id,
                        "stageName": str(stage.get("name", "Nodo")),
                        "type": "fan_in",
                        "severity": "high" if average_hours >= 36 else "medium",
                        "averageHours": round_hours(average_hours),
                        "expectedHours": float(stage.get("slaHours", 24)),
                        "indegree": indegree_value,
                        "outdegree": outdegree_value,
                        "description": "El nodo acumula muchas entradas y un tiempo promedio alto, por lo que puede congestionar el flujo.",
                        "recommendation": build_fan_in_recommendation(stage, department_name, role_name),
                    },
                )

            if all_paths and coverage == len(all_paths) and average_hours >= 24:
                add_bottleneck(
                    bottlenecks,
                    added,
                    {
                        "stageId": stage_id,
                        "stageName": str(stage.get("name", "Nodo")),
                        "type": "critical_path",
                        "severity": "high" if average_hours >= 36 else "medium",
                        "averageHours": round_hours(average_hours),
                        "expectedHours": float(stage.get("slaHours", 24)),
                        "indegree": indegree_value,
                        "outdegree": outdegree_value,
                        "description": "Todos los caminos del workflow pasan por este nodo y su tiempo promedio es alto.",
                        "recommendation": build_critical_path_recommendation(stage, department_name, role_name),
                    },
                )

            if node_type.lower() == "process" and average_hours >= 24 and coverage >= max(1, len(all_paths) // 2):
                add_bottleneck(
                    bottlenecks,
                    added,
                    {
                        "stageId": stage_id,
                        "stageName": str(stage.get("name", "Nodo")),
                        "type": "role_overload",
                        "severity": "high" if average_hours >= 36 else "medium",
                        "averageHours": round_hours(average_hours),
                        "expectedHours": float(stage.get("slaHours", 24)),
                        "indegree": indegree_value,
                        "outdegree": outdegree_value,
                        "description": "El mismo rol o responsable concentra gran parte del trabajo del workflow y tarda mas de lo esperado.",
                        "recommendation": build_role_overload_recommendation(stage, department_name, role_name),
                    },
                )

    for stage in stages:
        stage_id = str(stage.get("id", ""))
        node_type = str(stage.get("nodeType", ""))
        if node_type.lower() != "fork":
            continue

        branch_targets = adjacency.get(stage_id, [])
        if len(branch_targets) < 2:
            continue

        convergence_count: dict[str, int] = defaultdict(int)
        for branch_target in branch_targets:
            for next_target in adjacency.get(branch_target, []):
                convergence_count[next_target] += 1

        for target_id, count in convergence_count.items():
            if count < 2:
                continue
            converged_stage = stages_by_id.get(target_id)
            if not converged_stage:
                continue
            average_hours = average_hours_by_stage.get(target_id, float(converged_stage.get("slaHours", 24)))
            add_bottleneck(
                bottlenecks,
                added,
                {
                    "stageId": target_id,
                    "stageName": str(converged_stage.get("name", "Nodo")),
                    "type": "parallelization",
                    "severity": "high" if average_hours >= 24 else "medium",
                    "averageHours": round_hours(average_hours),
                    "expectedHours": float(converged_stage.get("slaHours", 24)),
                    "indegree": indegree.get(target_id, 0),
                    "outdegree": outdegree.get(target_id, 0),
                    "description": "El flujo se bifurca, pero las ramas vuelven muy rapido al mismo nodo, por lo que casi no se aprovecha la paralelizacion.",
                    "recommendation": "Mueve validaciones posteriores a cada rama, agrega una union explicita y evita reconverger tan pronto en el mismo responsable.",
                },
            )

    opportunities: list[dict[str, Any]] = []
    for stage in stages:
        stage_id = str(stage.get("id", ""))
        if str(stage.get("nodeType", "")).lower() != "fork":
            continue
        branch_targets = adjacency.get(stage_id, [])
        if len(branch_targets) >= 2:
            opportunities.append(
                {
                    "stageIds": branch_targets,
                    "reason": f'La bifurcacion desde "{stage.get("name", "Fork")}" puede redistribuir mejor carga si cada rama resuelve mas trabajo antes de reconverger.',
                }
            )

    for bottleneck in bottlenecks:
        bottleneck_type = str(bottleneck.get("type", ""))
        if bottleneck_type.lower() in {"role_overload", "critical_path", "fan_in"}:
            opportunities.append(
                {
                    "stageIds": [str(bottleneck.get("stageId", ""))],
                    "reason": str(bottleneck.get("recommendation", "")),
                }
            )

    workflow_average_hours = sum(average_hours_by_stage.values())
    workflow_expected_hours = sum(float(stage.get("slaHours", 24)) for stage in stages)
    sample_procedures = 0
    if workflow_id:
        sample_procedures = get_db().procedures.count_documents({"workflowId": workflow_id})

    summary = (
        "No se detectaron cuellos de botella fuertes con las metricas actuales del workflow."
        if not bottlenecks
        else f"Se detectaron {len(bottlenecks)} posibles cuellos de botella con base en tiempos promedio, convergencia, sobrecarga por rol y estructura del grafo. Revisa primero los nodos de severidad alta y las recomendaciones de derivacion por condicion o reparto por roles."
    )

    return {
        "summary": summary,
        "workflowAverageHours": round_hours(workflow_average_hours),
        "workflowExpectedHours": round_hours(workflow_expected_hours),
        "sampleProcedures": sample_procedures,
        "bottlenecks": bottlenecks,
        "parallelizationOpportunities": opportunities,
        "enrichedStages": enriched_stages,
    }


def merge_bottleneck_analysis(local_analysis: dict[str, Any], parsed: dict[str, Any]) -> dict[str, Any]:
    local_by_stage: dict[str, dict[str, Any]] = {}
    for bottleneck in local_analysis.get("bottlenecks", []):
        local_by_stage[str(bottleneck.get("stageId", ""))] = bottleneck

    merged: list[dict[str, Any]] = []
    seen: set[str] = set()

    for bottleneck in parsed.get("bottlenecks", []):
        if not isinstance(bottleneck, dict):
            continue
        stage_id = str(bottleneck.get("stageId", ""))
        local = local_by_stage.get(stage_id)
        normalized: dict[str, Any] = {}
        if local:
            normalized.update(local)
        normalized.update(bottleneck)
        if stage_id and stage_id not in seen:
            seen.add(stage_id)
            merged.append(normalized)

    for bottleneck in local_analysis.get("bottlenecks", []):
        stage_id = str(bottleneck.get("stageId", ""))
        if stage_id and stage_id not in seen:
            seen.add(stage_id)
            merged.append(bottleneck)

    return {
        "summary": str(parsed.get("summary", local_analysis.get("summary", ""))),
        "workflowAverageHours": local_analysis.get("workflowAverageHours", 0),
        "workflowExpectedHours": local_analysis.get("workflowExpectedHours", 0),
        "sampleProcedures": local_analysis.get("sampleProcedures", 0),
        "bottlenecks": merged,
        "parallelizationOpportunities": parsed.get(
            "parallelizationOpportunities",
            local_analysis.get("parallelizationOpportunities", []),
        ),
    }


def strip_enriched_stages(analysis: dict[str, Any]) -> dict[str, Any]:
    result = dict(analysis)
    result.pop("enrichedStages", None)
    return result


def build_local_worky_suggestions(
    workflow_name: str,
    stages: list[dict[str, Any]],
    transitions: list[dict[str, Any]],
) -> dict[str, Any]:
    suggestions: list[dict[str, Any]] = []
    indegree: dict[str, int] = defaultdict(int)
    outdegree: dict[str, int] = defaultdict(int)

    for transition in transitions:
        from_id = str(transition.get("fromStageId", ""))
        to_id = str(transition.get("toStageId", ""))
        if from_id:
            outdegree[from_id] += 1
        if to_id:
            indegree[to_id] += 1

    start_nodes = [stage for stage in stages if str(stage.get("nodeType", "")).lower() == "start"]
    end_nodes = [stage for stage in stages if str(stage.get("nodeType", "")).lower() == "end"]
    root_nodes = sorted(
        [
            stage
            for stage in stages
            if str(stage.get("nodeType", "")).lower() != "start"
            and indegree.get(str(stage.get("id", "")), 0) == 0
        ],
        key=lambda stage: int(stage.get("order", 0)),
    )
    leaf_nodes = [
        stage
        for stage in stages
        if str(stage.get("nodeType", "")).lower() != "end"
        and outdegree.get(str(stage.get("id", "")), 0) == 0
    ]

    if not start_nodes:
        actions = [
            worky_action(
                "type",
                "create_stage",
                "placeholderId",
                "worky_start",
                "name",
                "Inicio",
                "description",
                "Nodo de inicio agregado por Worky",
                "nodeType",
                "start",
                "order",
                1,
                "responsibleDepartmentName",
                None,
                "responsibleJobRoleName",
                None,
                "posX",
                50,
                "posY",
                50,
            )
        ]
        if root_nodes:
            actions.append(
                {
                    "type": "connect_stages",
                    "fromStageId": "worky_start",
                    "toStageId": str(root_nodes[0].get("id", "")),
                    "name": "",
                }
            )
        suggestions.append(
            {
                "id": "missing_start",
                "message": "Tu flujo no tiene un nodo de inicio. Â¿Deseas que lo agregue automÃ¡ticamente?",
                "reason": "El diagrama necesita un punto claro de entrada para iniciar el flujo.",
                "priority": "high",
                "actions": actions,
            }
        )

    if start_nodes:
        start = start_nodes[0]
        start_id = str(start.get("id", ""))
        if outdegree.get(start_id, 0) == 0:
            actions: list[dict[str, Any]] = []
            if root_nodes:
                actions.append(
                    {
                        "type": "connect_stages",
                        "fromStageId": start_id,
                        "toStageId": str(root_nodes[0].get("id", "")),
                        "name": "",
                    }
                )
            suggestions.append(
                {
                    "id": "start_needs_process",
                    "message": "Recuerda que el nodo Inicio debe estar conectado con un proceso. Quieres que te agregue ese proceso?",
                    "reason": "El nodo de inicio debe conducir al primer paso operativo del workflow.",
                    "priority": "high",
                    "actions": actions,
                }
            )

    if not end_nodes and leaf_nodes:
        actions = [
            worky_action(
                "type",
                "create_stage",
                "placeholderId",
                "worky_end",
                "name",
                "Fin",
                "description",
                "Nodo final agregado por Worky",
                "nodeType",
                "end",
                "order",
                len(stages) + 1,
                "responsibleDepartmentName",
                None,
                "responsibleJobRoleName",
                None,
                "posX",
                950,
                "posY",
                650,
            )
        ]
        for leaf in leaf_nodes:
            actions.append(
                {
                    "type": "connect_stages",
                    "fromStageId": str(leaf.get("id", "")),
                    "toStageId": "worky_end",
                    "name": "",
                }
            )
        suggestions.append(
            {
                "id": "missing_end",
                "message": "Tu diagrama no tiene un nodo final. Â¿Deseas agregar uno automÃ¡ticamente?",
                "reason": "Sin un nodo final, el flujo queda abierto y no expresa un cierre claro.",
                "priority": "high",
                "actions": actions,
            }
        )

    disconnected = sorted(
        [
            stage
            for stage in stages
            if str(stage.get("nodeType", "")).lower() not in {"start", "end"}
            and indegree.get(str(stage.get("id", "")), 0) == 0
            and outdegree.get(str(stage.get("id", "")), 0) == 0
        ],
        key=lambda stage: int(stage.get("order", 0)),
    )
    if len(disconnected) >= 2:
        actions = []
        for index in range(len(disconnected) - 1):
            actions.append(
                {
                    "type": "connect_stages",
                    "fromStageId": str(disconnected[index].get("id", "")),
                    "toStageId": str(disconnected[index + 1].get("id", "")),
                    "name": "",
                }
            )
        suggestions.append(
            {
                "id": "disconnected_processes",
                "message": "DetectÃ© procesos sin conexiÃ³n. Â¿Quieres que los conecte para mantener un flujo continuo?",
                "reason": "Hay nodos aislados que no participan en un flujo continuo.",
                "priority": "high",
                "actions": actions,
            }
        )

    for stage in stages:
        node_type = str(stage.get("nodeType", ""))
        stage_id = str(stage.get("id", ""))
        if node_type.lower() == "decision" and outdegree.get(stage_id, 0) < 2:
            actions = [
                worky_action(
                    "type",
                    "create_stage",
                    "placeholderId",
                    f"worky_yes_{stage_id}",
                    "name",
                    "Resultado Si",
                    "description",
                    "Salida sugerida por Worky",
                    "nodeType",
                    "end",
                    "order",
                    len(stages) + 1,
                    "responsibleDepartmentName",
                    None,
                    "responsibleJobRoleName",
                    None,
                    "posX",
                    650,
                    "posY",
                    400,
                ),
                worky_action(
                    "type",
                    "create_stage",
                    "placeholderId",
                    f"worky_no_{stage_id}",
                    "name",
                    "Resultado No",
                    "description",
                    "Salida sugerida por Worky",
                    "nodeType",
                    "end",
                    "order",
                    len(stages) + 2,
                    "responsibleDepartmentName",
                    None,
                    "responsibleJobRoleName",
                    None,
                    "posX",
                    950,
                    "posY",
                    400,
                ),
                {"type": "connect_stages", "fromStageId": stage_id, "toStageId": f"worky_yes_{stage_id}", "name": "Si"},
                {"type": "connect_stages", "fromStageId": stage_id, "toStageId": f"worky_no_{stage_id}", "name": "No"},
            ]
            suggestions.append(
                {
                    "id": "decision_missing_branches",
                    "message": "Recuerda que una decisiÃ³n debe tener al menos dos salidas (SÃ­/No). Â¿Quieres que las cree?",
                    "reason": "Las decisiones incompletas rompen la lÃ³gica condicional del workflow.",
                    "priority": "high",
                    "actions": actions,
                }
            )
            break

    for stage in stages:
        node_type = str(stage.get("nodeType", ""))
        stage_id = str(stage.get("id", ""))
        if node_type.lower() != "fork":
            continue
        branch_count = outdegree.get(stage_id, 0)
        has_join = any(str(item.get("nodeType", "")).lower() == "join" for item in stages)
        if branch_count >= 2 and not has_join:
            actions = [
                worky_action(
                    "type",
                    "create_stage",
                    "placeholderId",
                    "worky_join",
                    "name",
                    "Union paralela",
                    "description",
                    "Nodo join agregado por Worky",
                    "nodeType",
                    "join",
                    "order",
                    len(stages) + 1,
                    "responsibleDepartmentName",
                    None,
                    "responsibleJobRoleName",
                    None,
                    "posX",
                    650,
                    "posY",
                    560,
                )
            ]
            branch_targets = [
                str(transition.get("toStageId", ""))
                for transition in transitions
                if str(transition.get("fromStageId", "")) == stage_id
            ]
            for branch_target in branch_targets:
                actions.append(
                    {
                        "type": "connect_stages",
                        "fromStageId": branch_target,
                        "toStageId": "worky_join",
                        "name": "",
                    }
                )
            suggestions.append(
                {
                    "id": "missing_join_after_fork",
                    "message": "Has creado una bifurcaciÃ³n pero no una uniÃ³n. Â¿Deseas que agregue la uniÃ³n para cerrar el flujo paralelo?",
                    "reason": "Las ramas paralelas deben sincronizarse antes de continuar el flujo.",
                    "priority": "high",
                    "actions": actions,
                }
            )
        break

    unclear_names = [
        stage
        for stage in stages
        if str(stage.get("nodeType", "")).lower() == "process"
        and (
            bool(re.match(r"^(etapa|proceso|nodo)\s*\d*$", str(stage.get("name", "")).strip().lower()))
            or len(str(stage.get("name", "")).strip().lower()) <= 4
        )
    ]
    if unclear_names:
        actions = []
        index = 1
        for unclear in unclear_names:
            actions.append(
                {
                    "type": "update_stage",
                    "stageId": str(unclear.get("id", "")),
                    "name": f"Proceso {index}",
                    "description": "Nombre sugerido por Worky",
                }
            )
            index += 1
        suggestions.append(
            {
                "id": "unclear_names",
                "message": "Algunos procesos no tienen nombres claros. Â¿Deseas que sugiera nombres mÃ¡s descriptivos?",
                "reason": "Nombres genÃ©ricos dificultan entender el workflow.",
                "priority": "medium",
                "actions": actions,
            }
        )

    process_count = sum(1 for stage in stages if str(stage.get("nodeType", "")).lower() == "process")
    decision_count = sum(1 for stage in stages if str(stage.get("nodeType", "")).lower() == "decision")
    if process_count >= 4 and decision_count == 0:
        suggestions.append(
            {
                "id": "too_linear",
                "message": "Este flujo tiene muchos pasos lineales. Â¿Quieres que sugiera una decisiÃ³n para hacerlo mÃ¡s dinÃ¡mico?",
                "reason": "Un flujo demasiado lineal puede necesitar puntos de evaluaciÃ³n o bifurcaciÃ³n.",
                "priority": "low",
                "actions": [],
            }
        )

    if not suggestions:
        suggestions.append(
            {
                "id": "next_step_help",
                "message": "Â¿Necesitas ayuda? Puedo sugerirte el siguiente paso en tu diagrama.",
                "reason": "El diagrama no presenta errores estructurales graves en este momento.",
                "priority": "low",
                "actions": [],
            }
        )

    return {
        "assistantName": "Worky",
        "summary": f'Worky encontrÃ³ oportunidades para mejorar tu workflow "{workflow_name}".',
        "suggestions": suggestions,
    }


def process_diagram_command(body: dict[str, Any]) -> dict[str, Any]:
    command = body.get("command")
    stages = body.get("stages") or []
    transitions = body.get("transitions") or []
    history = body.get("history") or []
    departments = body.get("departments") or []
    job_roles = body.get("jobRoles") or []
    workflow_name = body.get("workflowName") or ""

    context = (
        "=== CONTEXTO DEL WORKFLOW ===\n"
        f"Nombre: {workflow_name}\n"
        "Departamentos disponibles (usa el 'name' exacto en responsibleDepartmentName):\n"
        f"{to_json(departments)}\n"
        "Roles disponibles por departamento (usa el 'name' exacto en responsibleJobRoleName):\n"
        f"{to_json(job_roles)}\n"
        "Reglas de formularios y flujo de datos:\n"
        "- Si un process captura datos, usa requiresForm=true y formDefinition.\n"
        "- Si una transicion debe pasar solo algunos campos, usa forwardConfig.mode='selected' con fieldNames exactos del formulario origen.\n"
        "- Si debe pasar todos los datos, usa forwardConfig.mode='all'.\n"
        "- Si solo deben pasar archivos, usa forwardConfig.mode='files-only' e includeFiles=true.\n"
        "Etapas actuales del diagrama:\n"
        f"{to_json(stages)}\n"
        "Transiciones actuales:\n"
        f"{to_json(transitions)}"
    )

    messages = list(history[max(0, len(history) - 6):])
    messages.append({"role": "user", "content": f"{command}\n\n{context}"})
    response = call_claude(WORKFLOW_GENERATION_PROMPT, DIAGRAM_MODEL, 8192, messages)
    return normalize_diagram_response(parse_json_response(response))


def analyze_bottlenecks(body: dict[str, Any]) -> dict[str, Any]:
    stages = body.get("stages") or []
    transitions = body.get("transitions") or []
    workflow_id = str(body.get("workflowId", "") or "")

    average_hours_by_stage = compute_average_hours_by_stage(workflow_id, stages)
    local_analysis = build_local_bottleneck_analysis(workflow_id, stages, transitions, average_hours_by_stage)
    enriched = local_analysis.get("enrichedStages", [])

    prompt = (
        f"Analiza este workflow:\nEtapas enriquecidas: {to_json(enriched)}"
        f"\nTransiciones: {to_json(transitions)}"
        f"\nAnalisis heuristico base: {to_json(local_analysis)}"
        "\nPrioriza detectar nodos lentos con muchas entradas, cuellos despues de bifurcaciones y nodos lentos compartidos por todos los caminos."
    )
    try:
        response = call_claude(
            BOTTLENECK_SYSTEM_PROMPT,
            HAIKU_MODEL,
            4096,
            [{"role": "user", "content": prompt}],
        )
        parsed = parse_json_response(response)
        return merge_bottleneck_analysis(local_analysis, parsed)
    except Exception:
        return strip_enriched_stages(local_analysis)


def analyze_worky_assistant(body: dict[str, Any]) -> dict[str, Any]:
    stages = body.get("stages") or []
    transitions = body.get("transitions") or []
    departments = body.get("departments") or []
    job_roles = body.get("jobRoles") or []
    workflow_name = body.get("workflowName") or ""

    context = (
        "=== WORKFLOW ===\n"
        f"Nombre: {workflow_name}\n"
        f"Departamentos:\n{to_json(departments)}\n"
        f"Roles:\n{to_json(job_roles)}\n"
        f"Etapas:\n{to_json(stages)}\n"
        f"Transiciones:\n{to_json(transitions)}\n"
    )

    try:
        response = call_claude(
            WORKY_ASSISTANT_PROMPT,
            HAIKU_MODEL,
            4096,
            [{"role": "user", "content": context}],
        )
        parsed = parse_json_response(response)
        return normalize_worky_suggestions(parsed)
    except Exception:
        return build_local_worky_suggestions(workflow_name, stages, transitions)


app = FastAPI(title="Workflow IA Service")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/diagram-command")
def diagram_command(body: dict[str, Any]) -> dict[str, Any]:
    return process_diagram_command(body)


@app.post("/bottleneck-analysis")
def bottleneck_analysis(body: dict[str, Any]) -> dict[str, Any]:
    return analyze_bottlenecks(body)


@app.post("/worky-suggestions")
def worky_suggestions(body: dict[str, Any]) -> dict[str, Any]:
    return analyze_worky_assistant(body)
