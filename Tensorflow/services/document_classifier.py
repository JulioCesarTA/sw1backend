"""
document_classifier.py
TensorFlow — clasifica el tipo de documento a partir de su contenido de texto.

Categorías: cargadas dinámicamente desde los campos FILE de los workflows en la BD.
Fallback a "OTRO" si no hay texto reconocible.
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


def _normalize(text: str) -> str:
    t = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    return re.sub(r"[^\w\s]", " ", t)


def _load_file_fields_from_workflows() -> list[str]:
    """
    Carga todos los nombres de campos FILE de todos los workflows.
    Devuelve lista de nombres únicos (son las categorías del clasificador).
    """
    field_names: set[str] = set()
    try:
        wf_list = api_get("/workflows")
        if not isinstance(wf_list, list):
            wf_list = []

        for wf in wf_list:
            wf_id = wf.get("id")
            if not wf_id:
                continue
            try:
                full = api_get(f"/workflows/{wf_id}")
                nodos = sorted(full.get("nodo", []),
                               key=lambda n: n.get("order", 9999))

                for nodo in nodos:
                    if (nodo.get("nodeType") or "").lower() in ("inicio","fin","start","end"):
                        continue
                    fd     = nodo.get("formDefinition") or {}
                    fields = fd.get("fields") or []
                    for f in fields:
                        if (f.get("type") or "").upper() == "FILE":
                            name = (f.get("name") or "").strip()
                            if name:
                                field_names.add(name)
            except Exception as e:
                logger.debug(f"  workflow {wf_id} skip: {e}")

        logger.info(f"DocumentClassifier: {len(field_names)} categorías cargadas desde workflows.")
    except Exception as e:
        logger.warning(f"DocumentClassifier: no se pudieron cargar workflows: {e}")

    return sorted(field_names)


def _generate_training_texts(field_name: str) -> list[str]:
    """
    Genera variantes de texto de entrenamiento a partir del nombre del campo.
    No usa ningún texto hardcodeado — todo viene del nombre real del campo.
    """
    n = _normalize(field_name)
    words = [w for w in n.split() if len(w) > 2]

    texts = [n]  # el nombre exacto

    if words:
        texts.append(" ".join(words))
        texts.append("documento " + " ".join(words))
        texts.append(" ".join(words) + " adjunto")
        texts.append("archivo " + " ".join(words))
        texts.append("copia " + " ".join(words))
        texts.append("original " + " ".join(words))
        texts.append(" ".join(words) + " requerido")
        texts.append("presentar " + " ".join(words))
        texts.append("adjuntar " + " ".join(words))
        texts.append(" ".join(words) + " del solicitante")

        # Variantes con palabras individuales
        for w in words:
            texts.append(w)
            texts.append(f"{w} documento")
            texts.append(f"certificado {w}")

    return texts


MAX_LEN  = 25
VOCAB_SZ = 800


class DocumentClassifier:
    def __init__(self):
        self.categories: list[str] = []

        # Cargar categorías reales desde la BD
        real_fields = _load_file_fields_from_workflows()

        if real_fields:
            self.categories = real_fields + ["OTRO"]
        else:
            logger.warning("DocumentClassifier: sin campos FILE en BD — usando solo OTRO.")
            self.categories = ["OTRO"]

        self._build_model()

    def _build_model(self):
        all_texts:  list[str] = []
        all_labels: list[int] = []

        for idx, cat in enumerate(self.categories):
            if cat == "OTRO":
                # Ejemplos genéricos para documentos no clasificables
                otro_texts = [
                    "documento adjunto referencia informacion adicional",
                    "informe tecnico datos estadisticos",
                    "comunicacion interna memorando nota",
                    "archivo sin clasificar referencia general",
                    "texto sin categoria especifica",
                ]
                for t in otro_texts:
                    all_texts.append(t)
                    all_labels.append(idx)
            else:
                for t in _generate_training_texts(cat):
                    all_texts.append(t)
                    all_labels.append(idx)

        if not all_texts:
            all_texts  = ["documento generico"]
            all_labels = [0]

        labels = np.array(all_labels, dtype=np.int32)

        self.vectorizer = keras.layers.TextVectorization(
            max_tokens=VOCAB_SZ,
            output_sequence_length=MAX_LEN,
        )
        self.vectorizer.adapt(all_texts)
        vocab_size = len(self.vectorizer.get_vocabulary())

        X = self.vectorizer(np.array(all_texts)).numpy()

        self.model = keras.Sequential([
            keras.layers.Embedding(vocab_size + 1, 32,
                                   input_length=MAX_LEN, mask_zero=True),
            keras.layers.GlobalAveragePooling1D(),
            keras.layers.Dense(64, activation="relu"),
            keras.layers.Dropout(0.3),
            keras.layers.Dense(len(self.categories), activation="softmax"),
        ])
        self.model.compile(optimizer="adam",
                           loss="sparse_categorical_crossentropy",
                           metrics=["accuracy"])

        logger.info(f"Entrenando DocumentClassifier con {len(self.categories)} "
                    f"categorías y {len(all_texts)} ejemplos …")
        self.model.fit(X, labels, epochs=150, verbose=0, batch_size=8)
        logger.info("DocumentClassifier listo.")

    def classify(self, text: str, top_k: int = 1) -> list[dict]:
        snippet = " ".join(_normalize(text).split()[:300])
        vec     = self.vectorizer(np.array([snippet])).numpy()
        probs   = self.model.predict(vec, verbose=0)[0]

        results = [
            {"type": self.categories[i], "prob": float(probs[i])}
            for i in range(len(self.categories))
        ]
        results.sort(key=lambda x: x["prob"], reverse=True)
        return results[:top_k]

    def classify_top(self, text: str) -> str:
        return self.classify(text, top_k=1)[0]["type"]
