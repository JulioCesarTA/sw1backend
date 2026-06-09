"""
form_filler.py
TensorFlow NLP — clasifica segmentos de voz en tipos de comando
y rellena campos de formulario con los valores detectados.

Tipos de comando (lo que el usuario siempre dice):
  FIELD_ASSIGN → "en el campo X ponele Y"
  GRID_INIT    → "en el campo X insertale N fila"
  GRID_COLUMN  → "en la columna X de la fila N ponele Y"
  UNKNOWN      → segmento sin comando reconocible
"""

import logging
import os
import re
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
from tensorflow import keras

logger = logging.getLogger(__name__)

CMD_TYPES = ["FIELD_ASSIGN", "GRID_INIT", "GRID_COLUMN", "UNKNOWN"]

TRAIN_TEXTS = [
    # FIELD_ASSIGN (0)
    "en el campo nombre ponele juan garcia",
    "en el campo fecha ponele 01 11 1998",
    "en el campo descripcion ponele averia en motor",
    "en el campo codigo ponele abc123",
    "en el campo estado ponele activo",
    "en el campo motivo ponele solicitud de baja",
    "en el campo titulo ponele reporte mensual",
    "en el campo observacion ponele sin novedad",
    "en el campo tipo ponele urgente",
    "campo direccion ponele calle cinco",
    # GRID_INIT (1)
    "en el campo productos insertale 1 fila",
    "en el campo items insertale una fila",
    "en el campo detalles insertale 2 filas",
    "en el campo juancarlos insertale 1 fila",
    "en el campo materiales insertale una fila",
    "en el campo registros insertale 1 fila",
    "en el campo tabla insertale una fila",
    "en el campo grilla insertale 1 fila",
    # GRID_COLUMN (2)
    "en la columna nombre de la fila 1 ponele juan",
    "en la columna cantidad de la fila 1 ponele 5",
    "en la columna precio de la fila 2 ponele 1000",
    "en la columna descripcion de la fila 1 ponele averia",
    "en la columna codigo de la fila 1 ponele abc",
    "en la columna juan de la fila 1 ponele xxxxx",
    "en la columna ssssss de la fila 1 ponele asdasd",
    "en la columna estado de la fila 2 ponele activo",
    # UNKNOWN (3)
    "hola como estas",
    "necesito ayuda con el tramite",
    "buenas tardes",
    "gracias",
    "quiero hacer un tramite nuevo",
    "no entiendo que debo decir",
]

TRAIN_LABELS = [0]*10 + [1]*8 + [2]*8 + [3]*6


_MONTHS = {
    "enero":1,"febrero":2,"marzo":3,"abril":4,"mayo":5,"junio":6,
    "julio":7,"agosto":8,"septiembre":9,"octubre":10,"noviembre":11,"diciembre":12,
}

def _parse_date(val: str) -> str:
    v = val.strip().lower()
    # "15 de marzo de 1990" o "15 marzo 1990"
    m = re.search(r'(\d{1,2})\s+(?:de\s+)?(\w+)\s+(?:de\s+)?(\d{4})', v)
    if m:
        day, mon_str, year = int(m.group(1)), m.group(2), int(m.group(3))
        month = _MONTHS.get(mon_str)
        if month:
            return f"{year:04d}-{month:02d}-{day:02d}"
    # "01 02 1992" o "01/02/1992" o "01-02-1992" → dd mm yyyy
    m = re.search(r'(\d{1,2})[/\-\s](\d{1,2})[/\-\s](\d{4})', v)
    if m:
        day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
        return f"{year:04d}-{month:02d}-{day:02d}"
    return val


def _norm(text: str) -> str:
    t = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    return re.sub(r"[^\w\s]", " ", t)


class FormFiller:
    def __init__(self):
        texts  = [_norm(t) for t in TRAIN_TEXTS]
        labels = np.array(TRAIN_LABELS, dtype=np.int32)

        self.vec = keras.layers.TextVectorization(max_tokens=300, output_sequence_length=20)
        self.vec.adapt(texts)
        vocab = len(self.vec.get_vocabulary())

        X = self.vec(np.array(texts)).numpy()

        self.model = keras.Sequential([
            keras.layers.Embedding(vocab + 1, 16, input_length=20, mask_zero=True),
            keras.layers.GlobalAveragePooling1D(),
            keras.layers.Dense(32, activation="relu"),
            keras.layers.Dense(len(CMD_TYPES), activation="softmax"),
        ])
        self.model.compile(optimizer="adam", loss="sparse_categorical_crossentropy",
                           metrics=["accuracy"])
        logger.info("Entrenando FormFiller TF …")
        self.model.fit(X, labels, epochs=100, verbose=0, batch_size=8)
        logger.info("FormFiller listo.")

    def _classify(self, segment: str) -> str:
        vec   = self.vec(np.array([_norm(segment)])).numpy()
        probs = self.model.predict(vec, verbose=0)[0]
        return CMD_TYPES[int(np.argmax(probs))]

    def fill_form(self, transcript: str, fields: list[dict]) -> dict:
        result:    dict             = {}
        applied:   list[dict]       = []
        warnings:  list[str]        = []
        grid_rows: dict[str, list]  = {}
        last_grid: str | None       = None

        segments = [s.strip() for s in re.split(r'[,;]\s*', transcript) if len(s.strip()) > 3]

        # normed name → actual field name (soporta guiones bajos y espacios)
        field_lookup:    dict[str, str]            = {}
        field_types:     dict[str, str]            = {}
        grid_col_lookup: dict[str, dict[str, str]] = {}  # field_name → {norm_col: actual_col}
        for f in fields:
            actual = f.get("name", "")
            n = _norm(actual)
            field_lookup[n] = actual
            field_lookup[n.replace("_", " ")] = actual
            field_types[actual] = f.get("type", "TEXT")
            if f.get("type") == "GRID":
                grid_col_lookup[actual] = {
                    _norm(c.get("name", "")): c.get("name", "")
                    for c in f.get("columns", [])
                }

        _PONELE = r'(?:ponele|ponerle|ponle|le pon)'

        for seg in segments:
            cmd = self._classify(seg)
            t   = _norm(seg)

            if cmd == "FIELD_ASSIGN":
                m = re.search(rf'campo\s+([\w\s]+?)\s+{_PONELE}\s+(.+)', t)
                if m:
                    actual = field_lookup.get(m.group(1).strip(), m.group(1).strip())
                    val    = m.group(2).strip()
                    if field_types.get(actual) == "DATE":
                        val = _parse_date(val)
                    result[actual] = val

            elif cmd == "GRID_INIT":
                m = re.search(r'campo\s+([\w\s]+?)\s+insertale', t)
                if m:
                    actual = field_lookup.get(m.group(1).strip(), m.group(1).strip())
                    grid_rows.setdefault(actual, [{}])
                    last_grid = actual

            elif cmd == "GRID_COLUMN":
                m = re.search(rf'columna\s+(\w+)\s+de\s+la\s+fila\s+(\d+)\s+{_PONELE}\s+(.+)', t)
                if m and last_grid is not None:
                    col_raw = m.group(1)
                    row_n   = int(m.group(2)) - 1
                    val     = m.group(3).strip()
                    # Resuelve casing real de la columna según la definición del formulario
                    col = grid_col_lookup.get(last_grid, {}).get(col_raw, col_raw)
                    rows = grid_rows[last_grid]
                    while len(rows) <= row_n:
                        rows.append({})
                    rows[row_n][col] = val

        result.update(grid_rows)

        for field in fields:
            fname = field.get("name", "")
            if field.get("type") == "FILE":
                continue
            if fname in result:
                val   = result[fname]
                label = f"{len(val)} fila(s)" if isinstance(val, list) else str(val)
                applied.append({"field": fname, "value": label})
            else:
                if re.search(rf'\b{re.escape(_norm(fname))}\b', _norm(transcript)):
                    warnings.append(
                        f"No pude detectar el valor para '{fname}'. "
                        f"Intenta: 'en el campo {fname} ponele [valor]'"
                    )

        if not result and not warnings:
            warnings.append(
                "No se detectó ningún valor. "
                "Di: 'en el campo [nombre] ponele [valor]' "
                "o 'en el campo [grilla] insertale 1 fila, en la columna [col] de la fila 1 ponele [valor]'"
            )

        return {
            "transcript":    transcript,
            "formData":      result,
            "appliedFields": applied,
            "warnings":      warnings,
        }
