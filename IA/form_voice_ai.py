import json
from typing import Any

from fastapi import HTTPException

from ai_common import DIAGRAM_MODEL, HAIKU_MODEL, call_claude


FORM_VOICE_PROMPT = """
Eres un asistente experto en interpretar formularios de workflow a partir de transcripciones de voz.
Responde SIEMPRE con JSON puro, sin markdown y sin texto fuera del JSON.

Formato obligatorio:
{
  "fieldValues": {},
  "appliedFields": [],
  "warnings": []
}

Reglas:
- Usa solo campos existentes en formDefinition.fields.
- No inventes campos nuevos.
- Convierte CHECKBOX a true o false.
- Convierte NUMBER a numero.
- Convierte DATE a formato YYYY-MM-DD cuando sea posible.
- Convierte EMAIL a email plano.
- Si un campo es FILE, no inventes archivos: agrega warning.
- Si un campo es GRID y el usuario dicta filas o columnas, devuelve la grilla completa resultante.
- Si el usuario pide marcar, tildar, activar o seleccionar un CHECKBOX, devuelve true.
- Si el usuario pide desmarcar, destildar, desactivar o quitar seleccion a un CHECKBOX, devuelve false.
- Si el formulario tiene una sola grilla y el usuario dice "agrega una fila", "nueva fila" o frases similares, interpreta que debe agregarse a esa grilla.
- Si el usuario dicta una fila de GRID diciendo "primera columna", "segunda columna", etc., usa las columnas reales del formDefinition en ese orden.
- Para GRID, devuelve el array completo final incluyendo filas previas en currentFormData mas las nuevas filas o cambios pedidos.
- No devuelvas una fila parcial si el usuario dio valores para varias columnas de la misma fila.
- Si el transcript no trae valor usable para un campo, no lo incluyas en fieldValues.
- appliedFields debe listar cada campo actualizado con su valor final.

Ejemplos:
- Transcript: "marca juan"
  Si "juan" es CHECKBOX => {"fieldValues":{"juan":true}}
- Transcript: "desmarca acepta terminos"
  Si "acepta terminos" es CHECKBOX => {"fieldValues":{"acepta terminos":false}}
- Transcript: "en la grilla productos agrega una fila, en la primera columna juan, en la segunda columna xxx"
  Si la grilla productos tiene columnas ["COLUMNA_1","COLUMNA_2"] => devuelve productos con la fila agregada usando esos nombres exactos.
"""

FORM_DESIGN_VOICE_PROMPT = """
Eres un asistente experto en modificar formularios de nodos de un workflow a partir de voz o texto.
Responde SIEMPRE con JSON puro, sin markdown y sin texto fuera del JSON.

Formato obligatorio:
{
  "targetNodoId": "",
  "requiresForm": true,
  "formDefinition": {
    "title": "Formulario",
    "fields": []
  },
  "patches": [],
  "changes": "",
  "warnings": []
}

Reglas:
- Usa solo nodos existentes en el contexto.
- Si hay selectedNodo y el usuario dice "este nodo", "nodo actual" o no aclara el nombre, usa selectedNodo.
- Si el usuario menciona un nodo por nombre y existe, usa su id real en targetNodoId.
- Solo modifica el formulario de un nodo de tipo proceso.
- Devuelve la definicion completa resultante del formulario, no solo el delta.
- Si el usuario pide cambios para varios nodos en una sola transcripcion, usa patches con una entrada por nodo.
- Cada item de patches debe tener targetNodoId, requiresForm, formDefinition, changes y warnings.
- Conserva campos existentes que no hayan sido modificados.
- Si el usuario pide eliminar un campo, devuelvelo removido de formDefinition.fields.
- Si el usuario pide eliminar varios campos, remueve todos los indicados.
- Si el usuario pide eliminar, quitar, borrar o remover todo el formulario de un nodo, responde con requiresForm=false y formDefinition=null.
- Conserva ids existentes cuando el campo ya existe.
- Si creas un campo nuevo y no hay id previo, genera un id simple tipo slug.
- Tipos permitidos: TEXT, NUMBER, DATE, FILE, EMAIL, CHECKBOX, GRID.
- Si el usuario dice text o texto usa TEXT.
- Si el usuario dice numero usa NUMBER.
- Si el usuario dice fecha usa DATE.
- Si el usuario dice archivo usa FILE.
- Si el usuario dice correo o email usa EMAIL.
- Si el usuario dice checkbox, check o verdadero/falso usa CHECKBOX.
- Si el usuario dice grilla, tabla o grid usa GRID.
- Un campo GRID debe incluir columns.
- Usa isRequired=true cuando el usuario diga obligatorio, requerido o mandatorio.
- Si el target es ambiguo o invalido, no inventes; devuelve warning y targetNodoId vacio.
- Si el usuario dicta columnas de una grilla, crea columns completas en el orden indicado.
- Si el usuario dice "primera columna", "segunda columna", etc., asigna nombres reales a cada columna.

Ejemplos:
- "agregale en su formulario el campo hola de tipo fecha"
  => agrega un campo nuevo llamado hola con type DATE.
- "agrega en el formulario una grilla productos, la primera columna es juan y la segunda columna es pedro"
  => agrega un campo GRID llamado productos con columns [{name:"juan"},{name:"pedro"}].
- "en el nodo secretaria academica elimina el campo observaciones"
  => devuelve la definicion completa del formulario sin el campo observaciones.
- "en el nodo juan elimina su formulario"
  => responde con requiresForm=false y formDefinition=null.
- "al nodo x agregale el campo y de tipo file y al nodo z quita el campo k"
  => responde con patches para ambos nodos.
"""


def process_form_voice_fill(body: dict[str, Any]) -> dict[str, Any]:
    transcript = str(body.get("transcript") or "").strip()
    form_definition = body.get("formDefinition") or {}
    current_form_data = body.get("currentFormData") or {}

    if not transcript:
        raise HTTPException(status_code=400, detail="Debes enviar el texto reconocido")

    context = (
        "=== TRANSCRIPCION ===\n"
        f"{transcript}\n\n"
        "=== FORMULARIO ===\n"
        f"{json.dumps(form_definition, ensure_ascii=False)}\n\n"
        "=== VALORES ACTUALES ===\n"
        f"{json.dumps(current_form_data, ensure_ascii=False)}"
    )
    messages = [{"role": "user", "content": context}]

    try:
        raw = call_claude(FORM_VOICE_PROMPT, HAIKU_MODEL, 4096, messages)
    except HTTPException as exc:
        if exc.status_code != 402:
            raise
        raw = call_claude(FORM_VOICE_PROMPT, DIAGRAM_MODEL, 4096, messages)

    parsed = parse_json_object(raw)
    fields_by_name = {
        str(field.get("name") or "").strip(): field
        for field in (form_definition.get("fields") or [])
        if str(field.get("name") or "").strip()
    }

    warnings = [str(item).strip() for item in (parsed.get("warnings") or []) if str(item).strip()]
    sanitized_values: dict[str, Any] = {}
    raw_field_values = parsed.get("fieldValues") or {}
    if isinstance(raw_field_values, dict):
        for field_name, raw_value in raw_field_values.items():
            definition = fields_by_name.get(str(field_name))
            if not definition:
                continue
            normalized = normalize_value_for_field(definition, raw_value)
            if normalized is None and str(definition.get("type") or "").upper() == "FILE":
                warnings.append(f"El campo {field_name} requiere subir archivo manualmente")
                continue
            if normalized is not None:
                sanitized_values[str(field_name)] = normalized

    applied_fields = [
        {"field": field_name, "value": value}
        for field_name, value in sanitized_values.items()
    ]
    return {
        "fieldValues": sanitized_values,
        "appliedFields": applied_fields,
        "warnings": warnings,
    }


def process_form_voice_design(body: dict[str, Any]) -> dict[str, Any]:
    transcript = str(body.get("transcript") or body.get("command") or "").strip()
    selected_nodo = body.get("selectedNodo") or {}
    nodos = body.get("nodo") or []

    if not transcript:
        raise HTTPException(status_code=400, detail="Debes enviar el texto reconocido")

    context = (
        "=== TRANSCRIPCION ===\n"
        f"{transcript}\n\n"
        "=== NODO SELECCIONADO ===\n"
        f"{json.dumps(selected_nodo, ensure_ascii=False)}\n\n"
        "=== NODOS DISPONIBLES ===\n"
        f"{json.dumps(nodos, ensure_ascii=False)}"
    )
    messages = [{"role": "user", "content": context}]

    try:
        raw = call_claude(FORM_DESIGN_VOICE_PROMPT, HAIKU_MODEL, 4096, messages)
    except HTTPException as exc:
        if exc.status_code != 402:
            raise
        raw = call_claude(FORM_DESIGN_VOICE_PROMPT, DIAGRAM_MODEL, 4096, messages)

    parsed = parse_json_object(raw)
    nodos_by_id = {
        str(item.get("id") or "").strip(): item
        for item in nodos
        if str(item.get("id") or "").strip()
    }
    raw_patches = parsed.get("patches")
    if isinstance(raw_patches, list) and raw_patches:
        normalized_patches = [
            normalize_form_design_patch(item, nodos_by_id, selected_nodo, transcript)
            for item in raw_patches
            if isinstance(item, dict)
        ]
        valid_patches = [item for item in normalized_patches if item]
        aggregated_warnings = [warning for item in valid_patches for warning in (item.get("warnings") or [])]
        return {
            "targetNodoId": valid_patches[0]["targetNodoId"] if valid_patches else "",
            "requiresForm": bool(valid_patches[0].get("requiresForm", True)) if valid_patches else True,
            "formDefinition": valid_patches[0].get("formDefinition") if valid_patches else None,
            "changes": str(parsed.get("changes") or "").strip(),
            "warnings": aggregated_warnings,
            "patches": valid_patches,
        }

    normalized_patch = normalize_form_design_patch(parsed, nodos_by_id, selected_nodo, transcript)
    if not normalized_patch:
        warnings = [str(item).strip() for item in (parsed.get("warnings") or []) if str(item).strip()]
        return {
            "targetNodoId": "",
            "requiresForm": True,
            "formDefinition": None,
            "changes": str(parsed.get("changes") or "").strip(),
            "warnings": warnings or ["No se pudo identificar el nodo a modificar"],
            "patches": [],
        }

    return {
        **normalized_patch,
        "patches": [normalized_patch],
    }


def normalize_value_for_field(field: dict[str, Any], value: Any) -> Any:
    field_type = str(field.get("type") or "TEXT").upper()
    if value is None:
        return None
    if field_type == "TEXT":
        return str(value).strip()
    if field_type == "NUMBER":
        return parse_number(value)
    if field_type in {"DATE", "EMAIL"}:
        return str(value).strip()
    if field_type == "CHECKBOX":
        return parse_checkbox(value)
    if field_type == "FILE":
        return None
    if field_type == "GRID":
        return normalize_grid(field, value)
    return str(value).strip()


def normalize_grid(field: dict[str, Any], value: Any) -> list[dict[str, Any]] | None:
    if isinstance(value, dict):
        value = value.get("rows") or value.get("items") or value.get("value")
    if not isinstance(value, list):
        return None
    columns = field.get("columns") or []
    columns_by_name = {
        str(column.get("name") or "").strip(): column
        for column in columns
        if str(column.get("name") or "").strip()
    }
    normalized_columns_by_name = {
        str(column.get("name") or "").strip().lower(): column
        for column in columns
        if str(column.get("name") or "").strip()
    }
    ordered_columns = [column for column in columns if str(column.get("name") or "").strip()]
    rows: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        row: dict[str, Any] = {}
        for column_name, raw_cell in item.items():
            raw_column_name = str(column_name).strip()
            definition = columns_by_name.get(raw_column_name) or normalized_columns_by_name.get(raw_column_name.lower())
            if definition is None and raw_column_name.isdigit():
                index = int(raw_column_name) - 1
                definition = ordered_columns[index] if 0 <= index < len(ordered_columns) else None
            if not definition:
                continue
            normalized = normalize_value_for_field(definition, raw_cell)
            if normalized is not None:
                row[str(definition.get("name"))] = normalized
        if row:
            rows.append(row)
    return rows


def parse_number(value: Any) -> int | float | None:
    if isinstance(value, (int, float)):
        return value
    text = str(value).strip().replace(",", ".")
    try:
        number = float(text)
    except ValueError:
        return None
    return int(number) if number.is_integer() else number


def parse_checkbox(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    normalized = str(value).strip().lower()
    if any(token in normalized for token in {"si", "sí", "true", "verdadero", "marcado", "marcar", "tildado", "tildar", "seleccionado", "seleccionar", "activo", "activar", "acepto", "checked"}):
        return True
    if any(token in normalized for token in {"no", "false", "falso", "desmarcado", "desmarcar", "destildado", "destildar", "inactivo", "desactivar", "rechazo", "unchecked"}):
        return False
    return None


def parse_json_object(text: str) -> dict[str, Any]:
    cleaned = text.replace("```json", "").replace("```", "").strip()
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        start = cleaned.find("{")
        end = cleaned.rfind("}")
        if start == -1 or end == -1 or end <= start:
            return {}
        try:
            parsed = json.loads(cleaned[start:end + 1])
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}


def mentions_selected_node(transcript: str) -> bool:
    normalized = transcript.strip().lower()
    return any(token in normalized for token in ["este nodo", "nodo actual", "este proceso", "proceso actual", "aqui", "acá", "aca"])


def transcript_requests_form_removal(transcript: str) -> bool:
    normalized = transcript.strip().lower()
    removal_tokens = ["elimina", "eliminar", "quita", "quitar", "borra", "borrar", "remueve", "remover"]
    form_tokens = ["formulario", "su formulario", "el formulario", "todo el formulario"]
    return any(token in normalized for token in removal_tokens) and any(token in normalized for token in form_tokens)


def normalize_form_design_patch(
    parsed: dict[str, Any],
    nodos_by_id: dict[str, Any],
    selected_nodo: dict[str, Any],
    transcript: str,
) -> dict[str, Any] | None:
    target_nodo_id = str(parsed.get("targetNodoId") or "").strip()
    target_nodo = nodos_by_id.get(target_nodo_id)
    warnings = [str(item).strip() for item in (parsed.get("warnings") or []) if str(item).strip()]

    if not target_nodo and selected_nodo and mentions_selected_node(transcript):
        fallback_id = str(selected_nodo.get("id") or "").strip()
        target_nodo = nodos_by_id.get(fallback_id) or selected_nodo
        target_nodo_id = fallback_id

    if not target_nodo_id or not target_nodo:
        return None

    node_type = str(target_nodo.get("nodeType") or "").strip().lower()
    if node_type != "proceso":
        return {
            "targetNodoId": target_nodo_id,
            "requiresForm": False,
            "formDefinition": None,
            "changes": "",
            "warnings": warnings + ["Solo se puede modificar el formulario de nodos tipo proceso"],
        }

    current_form = target_nodo.get("formDefinition") or {}
    requires_form = bool(parsed.get("requiresForm", True))
    patch_changes = str(parsed.get("changes") or "").strip()
    if transcript_requests_form_removal(transcript) or requires_form is False:
        return {
            "targetNodoId": target_nodo_id,
            "requiresForm": False,
            "formDefinition": None,
            "changes": patch_changes or "Se elimino el formulario del nodo",
            "warnings": warnings,
        }

    raw_form_definition = parsed["formDefinition"] if "formDefinition" in parsed else current_form
    normalized_form = normalize_form_definition(raw_form_definition, current_form)
    return {
        "targetNodoId": target_nodo_id,
        "requiresForm": True,
        "formDefinition": normalized_form,
        "changes": patch_changes,
        "warnings": warnings,
    }


def normalize_form_definition(raw_form_definition: Any, current_form: Any) -> dict[str, Any]:
    current_form = current_form if isinstance(current_form, dict) else {}
    raw_form_definition = raw_form_definition if isinstance(raw_form_definition, dict) else {}
    current_fields_by_name = {
        str(field.get("name") or "").strip().lower(): field
        for field in (current_form.get("fields") or [])
        if isinstance(field, dict) and str(field.get("name") or "").strip()
    }
    title = str(raw_form_definition.get("title") or current_form.get("title") or "Formulario").strip() or "Formulario"
    fields: list[dict[str, Any]] = []
    for index, raw_field in enumerate(raw_form_definition.get("fields") or []):
        if not isinstance(raw_field, dict):
            continue
        field_name = str(raw_field.get("name") or "").strip()
        if not field_name:
            continue
        current_field = current_fields_by_name.get(field_name.lower(), {})
        field_type = normalize_field_type(raw_field.get("type") or current_field.get("type"))
        field_id = str(raw_field.get("id") or current_field.get("id") or slugify(field_name)).strip() or slugify(field_name)
        field: dict[str, Any] = {
            "id": field_id,
            "name": field_name,
            "type": field_type,
            "isRequired": bool(raw_field.get("isRequired", raw_field.get("required", current_field.get("isRequired", False)))),
            "order": index + 1,
            "columns": [],
        }
        if field_type == "GRID":
            field["columns"] = normalize_grid_columns(raw_field.get("columns") or current_field.get("columns") or [])
        fields.append(field)
    return {"title": title, "fields": fields}


def normalize_grid_columns(raw_columns: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_columns, list):
        return []
    columns: list[dict[str, Any]] = []
    for index, raw_column in enumerate(raw_columns):
        if not isinstance(raw_column, dict):
            continue
        name = str(raw_column.get("name") or "").strip()
        if not name:
            continue
        columns.append({
            "id": str(raw_column.get("id") or slugify(name)).strip() or slugify(name),
            "name": name,
            "type": normalize_field_type(raw_column.get("type")),
            "order": index + 1,
        })
    return columns


def normalize_field_type(raw_type: Any) -> str:
    normalized = str(raw_type or "TEXT").strip().upper()
    aliases = {
        "TEXTO": "TEXT",
        "TEXT": "TEXT",
        "NUMERO": "NUMBER",
        "NÚMERO": "NUMBER",
        "NUMBER": "NUMBER",
        "FECHA": "DATE",
        "DATE": "DATE",
        "ARCHIVO": "FILE",
        "FILE": "FILE",
        "CORREO": "EMAIL",
        "EMAIL": "EMAIL",
        "CHECK": "CHECKBOX",
        "CHECKBOX": "CHECKBOX",
        "GRID": "GRID",
        "GRILLA": "GRID",
        "TABLA": "GRID",
    }
    return aliases.get(normalized, "TEXT")


def slugify(value: str) -> str:
    normalized = "".join(char.lower() if char.isalnum() else "_" for char in value.strip())
    compact = "_".join(part for part in normalized.split("_") if part)
    return compact or "campo"
