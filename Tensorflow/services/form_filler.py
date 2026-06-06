"""
form_filler.py
TensorFlow + regex — rellena campos de formulario desde transcript de voz.

Flujo:
  1. Modo explícito: "en el campo X ponele Y" — regex directo sobre el campo real.
  2. Modo semántico TF: clasifica segmentos en tipos universales (PERSONA, FECHA, etc.)
     y los asocia a campos por inferencia dinámica del nombre del campo.
     No tiene keywords de dominio hardcodeados.
"""

import logging
import os
import re
import numpy as np
from datetime import date

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
from tensorflow import keras

logger = logging.getLogger(__name__)

# ──────────────────────────────────────────────────────────────────────────
# Tipos de segmento — universales, no dependen del dominio
# ──────────────────────────────────────────────────────────────────────────
SEG_TYPES = ["PERSON", "LOCATION", "DATE", "DESCRIPTION", "OTHER"]

# Frases de entrenamiento — genéricas, aplican a cualquier workflow
TRAIN_TEXTS = [
    # PERSON (0) — cómo la gente dice su nombre o el de alguien
    "me llamo juan garcia", "soy maria lopez", "el solicitante es pedro torres",
    "mi nombre es carlos ramirez", "solicito como ana martinez",
    "el responsable es luis fernandez", "me identifico como rosa villa",
    "quien solicita es elena ruiz", "el titular es jorge mendez",
    "el cliente es pablo suarez", "mi nombre completo es sofia herrera",
    # LOCATION (1) — cómo la gente describe lugares
    "ubicado en la planta baja", "en el edificio norte piso dos",
    "en el area de trabajo", "en la oficina tres del bloque b",
    "calle principal numero cinco", "en el segundo piso del bloque a",
    "zona norte del sitio", "en el sector tres",
    "en el sotano del edificio central", "en el local numero cuatro",
    "el domicilio es calle ocho numero doce",
    # DATE (2) — cómo la gente dice fechas
    "el dia quince de mayo", "hoy es el tres de junio",
    "el primero de enero", "fecha el cinco de marzo",
    "el veinte de agosto", "el dia de hoy", "el diez del mes actual",
    "el doce de febrero de este año", "fecha de reporte el quince",
    "el ultimo dia del mes", "el cuatro de julio",
    # DESCRIPTION (3) — cómo la gente describe problemas o situaciones
    "la falla es que no funciona y hay daños",
    "el problema es que no enciende correctamente",
    "presenta daños y no opera",
    "descripcion el elemento no funciona",
    "tiene una averia en la parte principal",
    "hace ruido raro y se detiene solo",
    "no registra nada y esta roto",
    "vibra demasiado y tiene fugas evidentes",
    "da error constantemente y pierde configuracion",
    "el sistema se reinicia solo y pierde informacion",
    "la razon es que no cumple los requisitos",
    "el motivo es que vencio el plazo",
    # OTHER (4)
    "quiero reportar", "necesito ayuda", "por favor revisar",
    "solicito asistencia", "vengo a reportar un inconveniente",
    "quisiera informar sobre",
]

TRAIN_LABELS = [0]*11 + [1]*11 + [2]*11 + [3]*12 + [4]*6

MESES_ES = {
    "enero":1,"febrero":2,"marzo":3,"abril":4,"mayo":5,"junio":6,
    "julio":7,"agosto":8,"septiembre":9,"octubre":10,"noviembre":11,"diciembre":12,
}
NUMS_ES = {
    "uno":1,"dos":2,"tres":3,"cuatro":4,"cinco":5,"seis":6,"siete":7,"ocho":8,
    "nueve":9,"diez":10,"once":11,"doce":12,"trece":13,"catorce":14,"quince":15,
    "dieciseis":16,"diecisiete":17,"dieciocho":18,"diecinueve":19,"veinte":20,
    "veintiuno":21,"veintidos":22,"veintitres":23,"veinticuatro":24,"veinticinco":25,
    "veintiseis":26,"veintisiete":27,"veintiocho":28,"veintinueve":29,"treinta":30,
    "primero":1, "ultimo":28,
}

# ──────────────────────────────────────────────────────────────────────────
# Inferencia dinámica del tipo semántico de un campo
# Basada en palabras universales del lenguaje — no keywords de dominio
# ──────────────────────────────────────────────────────────────────────────
_DATE_KEYS     = {
    "fecha", "periodo", "dia", "mes", "ano", "tiempo", "plazo",
    "vencimiento", "vigencia", "inicio", "fin", "termino",
}
_PERSON_KEYS   = {
    "nombre", "solicitante", "usuario", "empleado", "funcionario",
    "responsable", "persona", "cliente", "titular", "quien",
    "propietario", "representante", "interesado", "beneficiario",
}
_LOCATION_KEYS = {
    "ubicacion", "lugar", "direccion", "domicilio", "area",
    "zona", "sector", "oficina", "planta", "piso", "local",
    "instalacion", "predio", "sitio",
}
_DESC_KEYS     = {
    "descripcion", "detalle", "falla", "averia", "problema",
    "motivo", "observacion", "comentario", "nota", "reporte",
    "razon", "causa", "justificacion", "antecedente", "situacion",
    "incidente", "queja", "reclamo", "solicitud",
}


def _infer_class_from_name(field_name: str) -> int | None:
    """
    Infiere el tipo semántico de un campo a partir de las palabras en su nombre.
    Retorna el índice en SEG_TYPES o None si no hay match.
    Funciona para cualquier dominio — sin keywords específicos del negocio.
    """
    words = set(_norm(field_name).split())
    if words & _DATE_KEYS:     return 2  # DATE
    if words & _PERSON_KEYS:   return 0  # PERSON
    if words & _LOCATION_KEYS: return 1  # LOCATION
    if words & _DESC_KEYS:     return 3  # DESCRIPTION
    return None


def _norm(text: str) -> str:
    t = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    return re.sub(r"[^\w\s]", " ", t)


class FormFiller:
    def __init__(self):
        texts  = [_norm(t) for t in TRAIN_TEXTS]
        labels = np.array(TRAIN_LABELS, dtype=np.int32)

        self.vec = keras.layers.TextVectorization(max_tokens=400, output_sequence_length=20)
        self.vec.adapt(texts)
        vocab = len(self.vec.get_vocabulary())

        X = self.vec(np.array(texts)).numpy()

        self.model = keras.Sequential([
            keras.layers.Embedding(vocab + 1, 32, input_length=20, mask_zero=True),
            keras.layers.GlobalAveragePooling1D(),
            keras.layers.Dense(64, activation="relu"),
            keras.layers.Dropout(0.3),
            keras.layers.Dense(len(SEG_TYPES), activation="softmax"),
        ])
        self.model.compile(optimizer="adam", loss="sparse_categorical_crossentropy",
                           metrics=["accuracy"])
        logger.info("Entrenando FormFiller TF …")
        self.model.fit(X, labels, epochs=120, verbose=0, batch_size=8)
        logger.info("FormFiller listo.")

    def _classify_segment(self, segment: str) -> tuple[str, float]:
        vec   = self.vec(np.array([_norm(segment)])).numpy()
        probs = self.model.predict(vec, verbose=0)[0]
        idx   = int(np.argmax(probs))
        return SEG_TYPES[idx], float(probs[idx])

    # ── Extractores por tipo ──────────────────────────────────────────────

    def _extract_name(self, text: str) -> str | None:
        t = _norm(text)
        m = re.search(
            r'(?:me llamo|soy|llama|nombre es|nombre completo es|solicita|solicitante es|'
            r'identifico como|titular es|cliente es|responsable es|es)\s+'
            r'([a-z]+(?: [a-z]+){1,3})',
            t
        )
        if m:
            val  = m.group(1).strip()
            stop = {"el","la","un","una","de","del","en","y","con","que","es","como","quien"}
            words = [w for w in val.split() if w not in stop]
            if words:
                return " ".join(words).title()
        words = text.split()
        caps  = [w for w in words if w and w[0].isupper() and len(w) > 2]
        if len(caps) >= 2:
            return " ".join(caps[:3])
        return None

    def _extract_location(self, text: str) -> str | None:
        t = _norm(text)
        patterns = [
            r'(?:ubicado en|ubicacion es|domicilio es|en el|en la|sector|zona|'
            r'area de|oficina|edificio|piso|bloque|calle|local)\s+([a-z0-9 ]{3,50})',
            r'(?:domicilio|planta)\s+([a-z0-9 ]{2,40})',
        ]
        for p in patterns:
            m = re.search(p, t)
            if m:
                return m.group(0).strip()
        return None

    def _extract_date(self, text: str) -> str | None:
        m = re.search(r'\b(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})\b', text)
        if m:
            d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            if y < 100:
                y += 2000
            try:
                return date(y, mo, d).strftime("%Y-%m-%d")
            except ValueError:
                pass

        t = _norm(text)
        p = re.search(
            r'(?:el\s+)?(\w+)\s+de\s+(' + '|'.join(MESES_ES.keys()) + r')'
            r'(?:\s+de\s+(\d{4}))?',
            t
        )
        if p:
            ds, ms, ys = p.group(1), p.group(2), p.group(3)
            day   = int(ds) if ds.isdigit() else NUMS_ES.get(ds)
            month = MESES_ES.get(ms)
            year  = int(ys) if ys else date.today().year
            if day and month:
                try:
                    return date(year, month, day).strftime("%Y-%m-%d")
                except ValueError:
                    pass

        if re.search(r'\bhoy\b', t):
            return date.today().strftime("%Y-%m-%d")
        return None

    def _extract_description(self, text: str) -> str | None:
        t = _norm(text)
        patterns = [
            r'(?:la falla es que|el problema es que|la razon es que|el motivo es que|'
            r'descripcion[:\s]+|falla[:\s]+|averia[:\s]+|problema[:\s]+|motivo[:\s]+|'
            r'observacion[:\s]+|presenta\s+)(.{10,200})',
            r'(?:hace|tiene|no\s+\w+).{5,150}',
        ]
        for p in patterns:
            m = re.search(p, t)
            if m:
                val = m.group(1) if m.lastindex else m.group(0)
                return val.strip()
        return None

    # ── Modo grilla ───────────────────────────────────────────────────────

    def _extract_grid_commands(self, transcript: str, fields: list[dict]) -> dict:
        t = _norm(transcript)
        result = {}
        grid_fields = [f for f in fields if f.get("type") == "GRID"]
        if not grid_fields:
            return result

        NUMS_POS = ["una","uno","dos","tres","cuatro","cinco","seis","siete","ocho","nueve","diez"]
        verb_pat = r'(?:ponele?|pon|es|coloca|ingresa|escrib[ei]|dice|pone|anota|valor)'

        for field in grid_fields:
            fname    = field.get("name", "")
            fname_n  = _norm(fname)
            fname_sp = fname_n.replace("_", " ")
            columns  = field.get("columns", [])

            m_start = re.search(
                rf'(?:grilla|tabla|grid)\s+(?:llamada?\s+)?(?:{re.escape(fname_n)}|{re.escape(fname_sp)})',
                t
            )
            if not m_start:
                m_start = re.search(
                    rf'(?:en\s+)?(?:el\s+campo\s+)?(?:{re.escape(fname_n)}|{re.escape(fname_sp)})'
                    rf'\s+(?:insertale?|agrega|añade|insert|nueva\s+fila)',
                    t
                )
            if not m_start and len(grid_fields) == 1:
                m_start = re.search(r'\b(?:grilla|tabla|grid)\b', t)
            if not m_start:
                continue

            block = t[m_start.end():]
            row   = {}
            for idx, col in enumerate(columns):
                cname    = col.get("name", "")
                cname_n  = _norm(cname)
                cname_sp = cname_n.replace("_", " ")
                num_str  = str(idx + 1)
                num_word = NUMS_POS[idx] if idx < len(NUMS_POS) else num_str

                pat_name = (
                    rf'(?:en\s+la\s+)?(?:columna\s+)?(?:{re.escape(cname_n)}|{re.escape(cname_sp)})'
                    rf'\s+{verb_pat}\s+(.+?)(?=(?:en\s+la\s+)?(?:columna\s+|\Z)|\Z)'
                )
                pat_num = (
                    rf'(?:en\s+la\s+)?(?:columna|col)\s+(?:{num_str}|{num_word})'
                    rf'\s+{verb_pat}\s+(.+?)(?=(?:en\s+la\s+)?(?:columna|col)\s+|\Z)'
                )
                cm = re.search(pat_name, block) or re.search(pat_num, block)
                if cm:
                    row[cname] = cm.group(1).strip()

            if row:
                result[fname] = [row]

        return result

    # ── Modo explícito ────────────────────────────────────────────────────

    def _extract_explicit_commands(self, transcript: str, fields: list[dict]) -> dict[str, str]:
        t          = _norm(transcript)
        field_names = [f.get("name", "") for f in fields]
        result: dict[str, str] = {}

        verb_pat = r'(?:ponele?|es|escrib[ei]|coloca|ingresa|dice|pone|anota|pon)'
        stop_pat = r'(?=\s*(?:en\s+)?(?:el\s+)?campo\s|\Z)'

        for fname in field_names:
            fname_n  = _norm(fname)
            fname_sp = fname_n.replace("_", " ")

            pattern = (
                rf'(?:en\s+)?(?:el\s+)?campo\s+'
                rf'(?:{re.escape(fname_n)}|{re.escape(fname_sp)})'
                rf'\s+(?:{verb_pat}\s+)?(.+?){stop_pat}'
            )
            m = re.search(pattern, t)
            if m:
                val = re.sub(r'^(?:que es|es|el|la|un|una|de|del)\s+', '',
                             m.group(1).strip())
                if val:
                    result[fname] = val
                    continue

            pattern2 = (
                rf'(?:^|\s)(?:{re.escape(fname_n)}|{re.escape(fname_sp)})'
                rf'\s+{verb_pat}\s+(.+?){stop_pat}'
            )
            m2 = re.search(pattern2, t)
            if m2:
                val = m2.group(1).strip()
                if val:
                    result[fname] = val

        return result

    # ── API pública ───────────────────────────────────────────────────────

    def fill_form(self, transcript: str, fields: list[dict]) -> dict:
        result:   dict        = {}
        applied:  list[dict]  = []
        warnings: list[str]   = []

        # Paso 1: modo explícito
        result.update(self._extract_explicit_commands(transcript, fields))

        # Paso 2: grillas
        result.update(self._extract_grid_commands(transcript, fields))

        # Paso 3: semántico TF para campos restantes
        remaining = [
            f for f in fields
            if f.get("name") not in result
            and f.get("type") not in ("FILE", "GRID")
        ]

        if remaining:
            raw_segs = re.split(r'[,\.;]\s*', transcript)
            segments = [s.strip() for s in raw_segs if len(s.strip()) > 3]

            # Clasificar segmentos del transcript
            class_texts: dict[int, list[str]] = {i: [] for i in range(len(SEG_TYPES))}
            for seg in segments:
                cls_name, prob = self._classify_segment(seg)
                cls_idx = SEG_TYPES.index(cls_name)
                class_texts[cls_idx if prob >= 0.35 else len(SEG_TYPES) - 1].append(seg)

            class_combined = {
                cls: " ".join(txts)
                for cls, txts in class_texts.items() if txts
            }

            for field in remaining:
                fname  = field.get("name", "")
                ftype  = field.get("type", "TEXT")
                val    = None

                # Inferir tipo semántico del campo por su nombre (dinámico)
                target_cls = _infer_class_from_name(fname)

                if ftype == "DATE" or target_cls == 2:  # DATE
                    val = (self._extract_date(class_combined.get(2, ""))
                           or self._extract_date(transcript))
                elif target_cls == 0:  # PERSON
                    val = (self._extract_name(class_combined.get(0, ""))
                           or self._extract_name(transcript))
                elif target_cls == 1:  # LOCATION
                    val = (self._extract_location(class_combined.get(1, ""))
                           or self._extract_location(transcript))
                elif target_cls == 3:  # DESCRIPTION
                    val = (self._extract_description(class_combined.get(3, ""))
                           or self._extract_description(transcript))

                if val:
                    result[fname] = val

        # Construir respuesta
        t_norm = _norm(transcript)
        for field in fields:
            fname = field.get("name", "")
            ftype = field.get("type", "")
            if ftype == "FILE":
                continue
            if fname in result:
                val   = result[fname]
                label = f"{len(val)} fila(s)" if isinstance(val, list) else str(val)
                applied.append({"field": fname, "value": label})
            else:
                fname_n  = _norm(fname)
                fname_sp = fname_n.replace("_", " ")
                if re.search(rf'\b(?:{re.escape(fname_n)}|{re.escape(fname_sp)})\b', t_norm):
                    warnings.append(
                        f"No pude detectar el valor para '{fname}'. "
                        f"Intenta: 'en el campo {fname} ponele [valor]'"
                    )

        if not result and not warnings:
            warnings.append(
                "No se detectó ningún valor. "
                "Di: 'en el campo [nombre] ponele [valor]' "
                "o 'en la grilla [nombre] insertale una fila en la columna [col] ponele [valor]'"
            )

        return {
            "transcript":   transcript,
            "formData":     result,
            "appliedFields": applied,
            "warnings":     warnings,
        }
