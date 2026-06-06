"""
NLP Service — TensorFlow Intent Classifier + NER.

Entrenados con datos reales de la BD:
  - IntentClassifier: aumentado con nombres reales de departamentos y workflows
  - NERModel:         entrenado con entidades reales (dept, user, workflow, status)
"""

import logging
import os
import re
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
from tensorflow import keras

from services.api_client import api_get

logger = logging.getLogger(__name__)

# ──────────────────────────────────────────────────────────────────────────
# Real context loader
# ──────────────────────────────────────────────────────────────────────────
def _load_real_entities() -> tuple[list[str], list[str], list[str], list[str]]:
    """
    Retorna (departments, workflows, users, statuses) reales desde la BD.
    """
    depts, workflows, users = [], [], []
    statuses = ["COMPLETADO", "PENDIENTE", "EN_PROGRESO", "RECHAZADO", "APROBADO"]
    try:
        data = api_get("/tramites/report-data")
        rows = data if isinstance(data, list) else []
        depts     = sorted({r.get("departmentName") or "" for r in rows if r.get("departmentName")})
        workflows = sorted({r.get("workflowName")   or "" for r in rows if r.get("workflowName")})
        users     = sorted({r.get("userName")        or "" for r in rows if r.get("userName")})
        logger.info(f"NLPService: entidades reales — {len(depts)} depts, "
                    f"{len(workflows)} workflows, {len(users)} usuarios.")
    except Exception as e:
        logger.warning(f"NLPService: no se pudieron cargar entidades reales: {e}")
    return depts, workflows, users, statuses


def _norm(text: str) -> str:
    t = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    return re.sub(r"[^\w\s]", " ", t)


# ──────────────────────────────────────────────────────────────────────────
# Intents — categorías fijas, ejemplos enriquecidos con datos reales
# ──────────────────────────────────────────────────────────────────────────
INTENTS = [
    "REPORT_BY_DEPT",
    "REPORT_BY_DATE",
    "REPORT_BY_USER",
    "REPORT_BY_STATUS",
    "REPORT_COMBINED",
    "REPORT_ALL",
]

# Plantillas base — sin nombres de departamento hardcodeados
_BASE_INTENT_TEXTS = [
    # REPORT_BY_DEPT (0)
    ("reporte del departamento", 0),
    ("dame los tramites del area", 0),
    ("muéstrame lo que hizo el departamento", 0),
    ("tramites del area de trabajo", 0),
    ("informe del sector", 0),
    ("actividad del departamento", 0),
    ("que proceso el area", 0),
    # REPORT_BY_DATE (1)
    ("reporte del mes de enero", 1),
    ("tramites entre enero y marzo", 1),
    ("que paso en el primer trimestre", 1),
    ("dame los datos de la semana pasada", 1),
    ("tramites de este mes", 1),
    ("datos del año pasado", 1),
    ("todo lo del mes anterior", 1),
    ("actividad del ultimo mes", 1),
    ("que paso entre enero y junio", 1),
    ("informe del periodo anterior", 1),
    ("reporte del periodo del 1 al 31", 1),
    # REPORT_BY_USER (2)
    ("que hizo el usuario", 2),
    ("tramites atendidos por el funcionario", 2),
    ("actividad del empleado", 2),
    ("que proceso el responsable este mes", 2),
    ("informe del empleado", 2),
    ("dame los tramites de ese usuario", 2),
    ("actividades de ese funcionario", 2),
    # REPORT_BY_STATUS (3)
    ("tramites completados", 3),
    ("dame los pendientes", 3),
    ("reporte de en proceso", 3),
    ("cuantos estan aprobados", 3),
    ("muestrame los rechazados", 3),
    ("tramites con estado completado", 3),
    ("los que estan en proceso", 3),
    ("cuantos tramites estan pendientes", 3),
    # REPORT_COMBINED (4)
    ("tramites del departamento en enero", 4),
    ("reporte del area completados en ese mes", 4),
    ("que atendio ese sector el mes pasado", 4),
    ("tramites completados del departamento en febrero", 4),
    ("actividad del usuario en ese departamento", 4),
    ("pendientes del area este mes", 4),
    ("tramites completados entre enero y marzo del area", 4),
    ("informe de tramites pendientes del area en ese año", 4),
    # REPORT_ALL (5)
    ("dame todo", 5),
    ("reporte general", 5),
    ("muestrame todos los tramites", 5),
    ("listado completo", 5),
    ("todos los datos", 5),
    ("reporte de todo el sistema", 5),
    ("general", 5),
    ("todo", 5),
    ("completo", 5),
]

def _build_intent_training(depts: list[str], workflows: list[str], users: list[str]):
    """Genera datos de entrenamiento de intents enriquecidos con entidades reales."""
    texts, labels = [], []

    for text, label in _BASE_INTENT_TEXTS:
        texts.append(_norm(text))
        labels.append(label)

    # Agregar ejemplos con departamentos reales (intent 0 y 4)
    for dept in depts:
        dn = _norm(dept)
        texts += [
            f"reporte del departamento {dn}",
            f"dame los tramites del area {dn}",
            f"que proceso {dn}",
            f"informe del sector {dn}",
        ]
        labels += [0, 0, 0, 0]

        texts += [
            f"tramites del departamento {dn} en enero",
            f"completados de {dn} este mes",
            f"pendientes del area {dn}",
        ]
        labels += [4, 4, 4]

    # Agregar ejemplos con workflows reales (intent 0 y 4)
    for wf in workflows:
        wn = _norm(wf)
        texts += [
            f"reporte del workflow {wn}",
            f"tramites del flujo {wn}",
            f"dame todo de {wn}",
        ]
        labels += [0, 0, 0]

        texts += [
            f"tramites completados del flujo {wn}",
            f"pendientes del workflow {wn} en enero",
        ]
        labels += [4, 4]

    # Agregar ejemplos con usuarios reales (intent 2 y 4)
    for user in users:
        un = _norm(user)
        first = un.split()[0] if un.split() else un
        texts += [
            f"tramites de {un}",
            f"que hizo {first}",
            f"actividad del usuario {un}",
            f"que proceso {first} este mes",
        ]
        labels += [2, 2, 2, 2]

        texts += [
            f"tramites completados de {first} en enero",
            f"pendientes de {un} en ese departamento",
        ]
        labels += [4, 4]

    return texts, labels


# ──────────────────────────────────────────────────────────────────────────
# NER — BIO tagging con entidades reales
# ──────────────────────────────────────────────────────────────────────────
NER_TAGS = ["O", "B-DEPT", "I-DEPT", "B-DATE", "I-DATE",
            "B-USER", "I-USER", "B-STATUS", "I-STATUS"]
TAG2ID   = {t: i for i, t in enumerate(NER_TAGS)}

# Patrones base (estructurales, no entidades específicas)
_BASE_NER = [
    (["entre", "enero", "y", "marzo"],           ["O", "B-DATE", "O", "B-DATE"]),
    (["del", "mes", "de", "enero"],              ["O", "O", "O", "B-DATE"]),
    (["en", "febrero", "de", "dos", "mil"],      ["O", "B-DATE", "O", "I-DATE", "I-DATE"]),
    (["primer", "trimestre"],                    ["B-DATE", "I-DATE"]),
    (["este", "mes"],                            ["B-DATE", "I-DATE"]),
    (["semana", "pasada"],                       ["B-DATE", "I-DATE"]),
    (["mes", "pasado"],                          ["B-DATE", "I-DATE"]),
    (["completados"],                            ["B-STATUS"]),
    (["pendientes"],                             ["B-STATUS"]),
    (["en", "proceso"],                          ["B-STATUS", "I-STATUS"]),
    (["aprobados"],                              ["B-STATUS"]),
    (["rechazados"],                             ["B-STATUS"]),
]

def _tokenize(text: str) -> list[str]:
    return _norm(text).split()

def _tag_entity(tokens: list[str], entity_tokens: list[str],
                b_tag: str, i_tag: str) -> list[str]:
    """Genera tags BIO para una secuencia de tokens de entidad."""
    tags = ["O"] * len(tokens)
    n = len(entity_tokens)
    for i in range(len(tokens) - n + 1):
        if tokens[i:i+n] == entity_tokens:
            tags[i] = b_tag
            for j in range(1, n):
                tags[i+j] = i_tag
    return tags

def _merge_tags(base: list[str], overlay: list[str]) -> list[str]:
    return [o if o != "O" else b for b, o in zip(base, overlay)]

def _build_ner_training(depts: list[str], users: list[str], workflows: list[str]):
    """Genera datos NER enriquecidos con entidades reales de la BD."""
    examples = list(_BASE_NER)

    for dept in depts:
        dt = _tokenize(dept)
        n  = len(dt)
        b_tags = ["B-DEPT"] + ["I-DEPT"] * (n - 1)

        sentence1 = ["tramites", "del", "departamento"] + dt
        tags1 = ["O", "O", "O"] + b_tags
        examples.append((sentence1, tags1))

        sentence2 = ["reporte", "del", "area"] + dt
        tags2 = ["O", "O", "O"] + b_tags
        examples.append((sentence2, tags2))

        sentence3 = dt + ["proceso", "tramites"]
        tags3 = b_tags + ["O", "O"]
        examples.append((sentence3, tags3))

        if len(dt) >= 2:
            examples.append((dt, b_tags))

    for user in users:
        ut = _tokenize(user)
        n  = len(ut)
        b_tags = ["B-USER"] + ["I-USER"] * (n - 1)

        sentence1 = ["tramites", "de"] + ut
        tags1 = ["O", "O"] + b_tags
        examples.append((sentence1, tags1))

        sentence2 = ["actividad", "de"] + ut
        tags2 = ["O", "O"] + b_tags
        examples.append((sentence2, tags2))

        if ut:
            first = [ut[0]]
            examples.append((["que", "hizo"] + first, ["O", "O", "B-USER"]))

    for wf in workflows:
        wt = _tokenize(wf)
        n  = len(wt)
        b_tags = ["B-DEPT"] + ["I-DEPT"] * (n - 1)

        sentence1 = ["tramites", "del", "flujo"] + wt
        tags1 = ["O", "O", "O"] + b_tags
        examples.append((sentence1, tags1))

        sentence2 = ["reporte", "del", "workflow"] + wt
        tags2 = ["O", "O", "O"] + b_tags
        examples.append((sentence2, tags2))

    # Combinados (dept + date)
    for dept in depts[:3]:  # 3 ejemplos para no inflar demasiado
        dt = _tokenize(dept)
        n  = len(dt)
        sentence = ["tramites", "del", "area"] + dt + ["en", "enero", "completados"]
        tags = ["O", "O", "O"] + (["B-DEPT"] + ["I-DEPT"] * (n - 1)) + ["O", "B-DATE", "B-STATUS"]
        examples.append((sentence, tags))

    return examples


MAX_LEN  = 25
VOCAB_SZ = 600


# ──────────────────────────────────────────────────────────────────────────
# Intent Classifier
# ──────────────────────────────────────────────────────────────────────────
class IntentClassifier:
    def __init__(self, depts: list[str], workflows: list[str], users: list[str]):
        texts_raw, labels_raw = _build_intent_training(depts, workflows, users)
        texts  = texts_raw
        labels = np.array(labels_raw, dtype=np.int32)

        self.vectorizer = keras.layers.TextVectorization(
            max_tokens=VOCAB_SZ,
            output_sequence_length=MAX_LEN,
        )
        self.vectorizer.adapt(texts)
        vocab_size = len(self.vectorizer.get_vocabulary())
        X = self.vectorizer(np.array(texts)).numpy()

        self.model = keras.Sequential([
            keras.layers.Embedding(vocab_size + 1, 32,
                                   input_length=MAX_LEN, mask_zero=True),
            keras.layers.GlobalAveragePooling1D(),
            keras.layers.Dense(64, activation="relu"),
            keras.layers.Dropout(0.3),
            keras.layers.Dense(len(INTENTS), activation="softmax"),
        ])
        self.model.compile(optimizer="adam",
                           loss="sparse_categorical_crossentropy",
                           metrics=["accuracy"])
        logger.info(f"Entrenando IntentClassifier con {len(texts)} ejemplos …")
        self.model.fit(X, labels, epochs=150, verbose=0, batch_size=16)
        logger.info("IntentClassifier listo.")

    def predict(self, text: str) -> tuple[str, float]:
        norm  = _norm(text)
        vec   = self.vectorizer(np.array([norm])).numpy()
        probs = self.model.predict(vec, verbose=0)[0]
        idx   = int(np.argmax(probs))
        return INTENTS[idx], float(probs[idx])


# ──────────────────────────────────────────────────────────────────────────
# NER (BiLSTM word-level)
# ──────────────────────────────────────────────────────────────────────────
class NERModel:
    def __init__(self, depts: list[str], users: list[str], workflows: list[str]):
        training = _build_ner_training(depts, users, workflows)

        all_words  = [w for sent, _ in training for w in sent]
        self.vocab = ["[PAD]", "[UNK]"] + sorted(set(all_words))
        self.w2i   = {w: i for i, w in enumerate(self.vocab)}

        X, Y = [], []
        for words, tags in training:
            xi = [self.w2i.get(w, 1) for w in words]
            yi = [TAG2ID.get(t, 0) for t in tags]
            xi += [0] * (MAX_LEN - len(xi))
            yi += [0] * (MAX_LEN - len(yi))
            X.append(xi[:MAX_LEN])
            Y.append(yi[:MAX_LEN])

        X = np.array(X, dtype=np.int32)
        Y = np.array(Y, dtype=np.int32)

        self.model = keras.Sequential([
            keras.layers.Embedding(len(self.vocab) + 2, 32,
                                   input_length=MAX_LEN, mask_zero=True),
            keras.layers.Bidirectional(keras.layers.LSTM(32, return_sequences=True)),
            keras.layers.TimeDistributed(
                keras.layers.Dense(len(NER_TAGS), activation="softmax")
            ),
        ])
        self.model.compile(optimizer="adam",
                           loss="sparse_categorical_crossentropy",
                           metrics=["accuracy"])
        logger.info(f"Entrenando NERModel con {len(training)} ejemplos …")
        self.model.fit(X, Y, epochs=100, verbose=0, batch_size=4)
        logger.info("NERModel listo.")

    def predict(self, text: str) -> list[tuple[str, str]]:
        tokens = _norm(text).split()
        ids    = [self.w2i.get(t, 1) for t in tokens]
        ids   += [0] * (MAX_LEN - len(ids))
        ids    = ids[:MAX_LEN]

        preds  = self.model.predict(np.array([ids], dtype=np.int32), verbose=0)[0]
        result = []
        for i, token in enumerate(tokens[:MAX_LEN]):
            tag_id = int(np.argmax(preds[i]))
            result.append((token, NER_TAGS[tag_id]))
        return result


# ──────────────────────────────────────────────────────────────────────────
# NLPService (fachada)
# ──────────────────────────────────────────────────────────────────────────
class NLPService:
    def __init__(self):
        logger.info("NLPService: cargando entidades reales desde BD …")
        depts, workflows, users, _statuses = _load_real_entities()

        logger.info("NLPService: entrenando IntentClassifier …")
        self.intent_clf = IntentClassifier(depts, workflows, users)

        logger.info("NLPService: entrenando NERModel …")
        self.ner_model  = NERModel(depts, users, workflows)

        logger.info("NLPService listo.")

    def analyze(self, text: str) -> dict:
        intent, confidence = self.intent_clf.predict(text)
        ner_tokens         = self.ner_model.predict(text)
        return {
            "intent":     intent,
            "confidence": round(confidence, 3),
            "ner_tokens": ner_tokens,
            "raw_text":   text,
        }
