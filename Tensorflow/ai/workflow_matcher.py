import json
import logging
import os
from difflib import SequenceMatcher
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
from tensorflow import keras

from core.api_client import api_get, refresh_token
from ai.text_utils import normalize as _normalize, get_first_process_nodo as _get_first_process_nodo

logger = logging.getLogger(__name__)

FUZZY_THRESHOLD = 0.82
MIN_WORD_LEN    = 3


def _fuzzy_word_match(fw: str, doc_words: list[str]) -> bool:
    if len(fw) <= MIN_WORD_LEN:
        return fw in doc_words
    for dw in doc_words:
        if abs(len(fw) - len(dw)) > 3:
            continue  # diferencia de longitud muy grande → no comparar
        ratio = SequenceMatcher(None, fw, dw).ratio()
        if ratio >= FUZZY_THRESHOLD:
            return True
    return False


def _field_covered_by_texts(field_name: str, check_texts: list[str]) -> bool:
    field_n     = _normalize(field_name)
    field_words = [w for w in field_n.split() if len(w) > MIN_WORD_LEN]
    if not field_words:
        # Campo de 1-2 letras: comparación directa
        return any(field_n in _normalize(t) for t in check_texts if t.strip())

    needed = max(1, len(field_words) // 2)

    for text in check_texts:
        if not text.strip():
            continue
        norm_text  = _normalize(text)
        doc_words  = norm_text.split()
        matched = 0
        for fw in field_words:
            # Primero intento exacto (más rápido)
            if fw in norm_text:
                matched += 1
            elif _fuzzy_word_match(fw, doc_words):
                matched += 1
        if matched >= needed:
            return True
    return False


class WorkflowMatcher:
    """
    Empareja texto del usuario con el workflow más adecuado.
    Score = 35% similitud coseno (embedding TF) + 65% cobertura de campos requeridos.
    """
    def __init__(self, db=None):
        self.workflows: list[dict] = []
        self.field_requirements: dict[str, dict] = {}  # wfId → {"required": [...], "optional": [...]}
        self.vectorizer = None
        self.encoder    = None
        self.wf_embeddings: list[np.ndarray] = []

        refresh_token()
        self._load_workflows()

        if self.workflows:
            self._build_encoder()
            logger.info(f"WorkflowMatcher listo — {len(self.workflows)} workflows.")
        else:
            logger.warning("WorkflowMatcher: sin workflows.")

    def reload(self):
        """Re-carga workflows desde Spring Boot y re-construye el encoder."""
        refresh_token()
        self._load_workflows()
        if self.workflows:
            self._build_encoder()
        logger.info(f"WorkflowMatcher recargado — {len(self.workflows)} workflows.")

    def _load_workflows(self):
        try:
            raw = api_get("/workflows")
            if isinstance(raw, str):
                raw = json.loads(raw)
            self.workflows = [
                {"id": w.get("id",""), "name": w.get("name","Workflow"), "description": w.get("description","")}
                for w in (raw if isinstance(raw, list) else [])
                if w.get("id") and w.get("name")
            ]
        except Exception as e:
            logger.warning(f"No se pudieron cargar workflows: {e}")
            self.workflows = []
            return

        for w in self.workflows:
            wf_id = w["id"]
            try:
                nodos = api_get(f"/workflows/{wf_id}").get("nodo", [])
                first_process = _get_first_process_nodo(nodos)
                required, optional = [], []
                if first_process:
                    fields = (first_process.get("formDefinition") or {}).get("fields") or []
                    for f in fields:
                        name = (f.get("name") or "").strip()
                        if not name:
                            continue
                        is_req = bool(f.get("required") or f.get("isRequired"))
                        if is_req:
                            required.append(name)
                        else:
                            optional.append(name)
                self.field_requirements[wf_id] = {"required": required, "optional": optional}
                logger.debug(f"  {w['name']}: {len(required)} req, {len(optional)} opt")
            except Exception:
                self.field_requirements[wf_id] = {"required": [], "optional": []}

        logger.info(f"WorkflowMatcher: {len(self.workflows)} workflows cargados (campos del nodo 2).")

    def _build_encoder(self):
        wf_texts = [self._wf_text(w) for w in self.workflows]
        wf_texts = [t if t.strip() else "workflow" for t in wf_texts]

        self.vectorizer = keras.layers.TextVectorization(
            max_tokens=600,
            output_sequence_length=30,
        )
        self.vectorizer.adapt(wf_texts)
        vocab_size = len(self.vectorizer.get_vocabulary())

        inp = keras.Input(shape=(30,), dtype=tf.int32)
        x   = keras.layers.Embedding(vocab_size + 1, 32, mask_zero=True)(inp)
        out = keras.layers.GlobalAveragePooling1D()(x)
        self.encoder = keras.Model(inp, out)

        # Entrena el embedding para que distinga entre workflows.
        # Con 2+ workflows genera muestras aumentadas por workflow y entrena
        # un clasificador (la cabeza se descarta; el encoder queda entrenado).
        if len(self.workflows) >= 2:
            self._train_encoder(wf_texts)

        self.wf_embeddings = [self._encode(self._wf_text(w)) for w in self.workflows]
        logger.info(f"WorkflowMatcher encoder {'entrenado' if len(self.workflows) >= 2 else 'sin entrenar (1 workflow)'}")

    def _augment_wf_texts(self, idx: int, full_text: str) -> list[str]:
        """Genera variaciones del texto de un workflow para aumentar datos de entrenamiento."""
        w   = self.workflows[idx]
        req = self.field_requirements.get(w["id"], {})
        all_fields = req.get("required", []) + req.get("optional", [])

        samples = [full_text, _normalize(w["name"])]

        # Descripción partida en mitades
        words = full_text.split()
        if len(words) > 6:
            mid = len(words) // 2
            samples.append(" ".join(words[:mid]))
            samples.append(" ".join(words[mid:]))

        # Nombre + cada campo individual
        for f in all_fields:
            samples.append(_normalize(f"{w['name']} {f}"))

        # Todos los campos juntos
        if all_fields:
            samples.append(_normalize(" ".join(all_fields)))

        # Word-dropout (×6): elimina 30% de palabras al azar para simular inputs parciales
        rng = np.random.default_rng(abs(hash(w["id"])) % (2**31))
        for _ in range(6):
            keep = rng.random(len(words)) > 0.3
            aug  = " ".join(wd for wd, k in zip(words, keep) if k)
            if aug.strip():
                samples.append(aug)

        return [s for s in samples if s.strip()]

    def _train_encoder(self, wf_texts: list[str]):
        """Entrena el encoder como clasificador de workflows; la cabeza se descarta."""
        X_texts, y_labels = [], []
        for i, full_text in enumerate(wf_texts):
            for sample in self._augment_wf_texts(i, full_text):
                X_texts.append(sample)
                y_labels.append(i)

        X_vecs = self.vectorizer(np.array(X_texts)).numpy()
        y_np   = np.array(y_labels, dtype=np.int32)

        # Cabeza de clasificación sobre el encoder (compartida en pesos)
        inp_cls = keras.Input(shape=(30,), dtype=tf.int32)
        emb     = self.encoder(inp_cls)
        logits  = keras.layers.Dense(len(self.workflows), activation="softmax")(emb)
        train_model = keras.Model(inp_cls, logits)
        train_model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
        train_model.fit(X_vecs, y_np, epochs=80, batch_size=8, verbose=0)
        # self.encoder comparte pesos con train_model → ya quedó entrenado
        logger.info(f"  Encoder entrenado — {len(X_texts)} muestras, {len(self.workflows)} clases")

    def match_with_doc_texts(self,
                              user_text: str,
                              doc_texts: list[str],
                              all_text:  str = "") -> list[dict]:
        if not self.workflows or self.encoder is None:
            return []

        query_emb = self._encode(_normalize(all_text or user_text))

        # Textos donde buscar cobertura de campos: documentos + texto del usuario
        all_check_texts = doc_texts + ([user_text] if user_text and user_text.strip() else [])

        results = []
        for i, w in enumerate(self.workflows):
            wid   = w["id"]
            w_emb = self.wf_embeddings[i]

            cos = float(
                np.dot(query_emb, w_emb)
                / (np.linalg.norm(query_emb) * np.linalg.norm(w_emb) + 1e-8)
            )

            req             = self.field_requirements.get(wid, {})
            required_fields = req.get("required", [])
            optional_fields = req.get("optional", [])

            present_req = [f for f in required_fields
                           if _field_covered_by_texts(f, all_check_texts)]
            missing_req = [f for f in required_fields if f not in present_req]
            present_opt = [f for f in optional_fields
                           if _field_covered_by_texts(f, all_check_texts)]

            docs_complete = len(missing_req) == 0 and len(required_fields) > 0
            doc_score     = len(present_req) / len(required_fields) if required_fields else 0.0
            total         = (0.35 * max(0, cos) + 0.65 * doc_score) if required_fields else max(0, cos)

            if docs_complete:
                total = 1.0

            results.append({
                "workflowId":      wid,
                "workflowName":    w["name"],
                "workflowDescription": w.get("description", ""),
                "score":           round(total * 100, 1),
                "cosSim":          round(max(0, cos) * 100, 1),
                "confidence":      self._confidence(total),
                "requiredDocs":    required_fields,
                "optionalDocs":    optional_fields,
                "presentRequired": present_req,
                "missingRequired": missing_req,
                "presentOptional": present_opt,
                "docsComplete":    docs_complete,
            })

        return sorted(results, key=lambda x: x["score"], reverse=True)[:3]

    def _wf_text(self, w: dict) -> str:
        req   = self.field_requirements.get(w["id"], {})
        parts = [w["name"], w.get("description", "")]
        parts += req.get("required", [])
        parts += req.get("optional", [])
        return _normalize(" ".join(p for p in parts if p))

    def _encode(self, text: str) -> np.ndarray:
        vec = self.vectorizer(np.array([text or "workflow"])).numpy()
        return self.encoder.predict(vec, verbose=0)[0]

    def _confidence(self, score: float) -> str:
        if score >= 0.70: return "Alta"
        if score >= 0.40: return "Media"
        return "Baja"
