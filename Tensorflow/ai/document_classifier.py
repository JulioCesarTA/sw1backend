import logging
import os
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
from tensorflow import keras

from core.api_client import api_get
from ai.text_utils import normalize as _normalize, get_first_process_nodo as _get_first_process_nodo

logger = logging.getLogger(__name__)


def _load_all_fields_from_workflows() -> list[str]:
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
                full  = api_get(f"/workflows/{wf_id}")
                nodos = full.get("nodo", [])
                nodo  = _get_first_process_nodo(nodos)
                if not nodo:
                    continue
                fields = (nodo.get("formDefinition") or {}).get("fields") or []
                for f in fields:
                    name = (f.get("name") or "").strip()
                    if name:
                        field_names.add(name)
            except Exception as e:
                logger.debug(f"  workflow {wf_id} skip: {e}")

        logger.info(f"DocumentClassifier: {len(field_names)} categorías (todos los campos del nodo 2).")
    except Exception as e:
        logger.warning(f"DocumentClassifier: no se pudieron cargar workflows: {e}")

    return sorted(field_names)


def _generate_training_texts(field_name: str) -> list[str]:
    n = _normalize(field_name)
    words = [w for w in n.split() if len(w) > 2]
    if not words:
        return [n]
    joined = " ".join(words)
    return [
        n,
        joined,
        f"documento {joined}",
        f"archivo {joined}",
        f"{joined} adjunto",
        f"adjuntar {joined}",
        f"campo {joined}",
        f"dato {joined}",
        f"informacion {joined}",
    ]


MAX_LEN  = 25
VOCAB_SZ = 800


class DocumentClassifier:
    def __init__(self):
        self.categories: list[str] = []

        real_fields = _load_all_fields_from_workflows()

        if real_fields:
            self.categories = real_fields + ["OTRO"]
        else:
            logger.warning("DocumentClassifier: sin campos en BD — usando solo OTRO.")
            self.categories = ["OTRO"]

        self._build_model()

    def _build_model(self):
        all_texts:  list[str] = []
        all_labels: list[int] = []

        for idx, cat in enumerate(self.categories):
            if cat == "OTRO":
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

        logger.info(f"Entrenando DocumentClassifier: {len(self.categories)} categorías, "
                    f"{len(all_texts)} ejemplos …")
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

