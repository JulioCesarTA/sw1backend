"""
workflow_matcher.py
TensorFlow — empareja texto del usuario con el workflow más adecuado.

Fuente de datos:
  - Workflows + nodos + form definitions: Spring Boot REST API (puerto 8080)
  - Campos obligatorios: primer nodo de cada workflow con campos FILE
"""

import json
import logging
import os
import re
import requests
import numpy as np

os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
import tensorflow as tf
from tensorflow import keras

logger = logging.getLogger(__name__)

SPRING_API = os.getenv("SPRING_API_URL", "http://localhost:8080/api")

_token: str = ""


def _get_token() -> str:
    global _token
    try:
        r = requests.post(f"{SPRING_API}/auth/login",
                          json={"email": os.getenv("SPRING_EMAIL", "juan@gmail.com"),
                                "password": os.getenv("SPRING_PASSWORD", "julioavila")},
                          timeout=8)
        r.raise_for_status()
        data = r.json()
        _token = data.get("token") or data.get("accessToken") or ""
    except Exception as e:
        logger.warning(f"WorkflowMatcher: no se pudo obtener token: {e}")
        _token = ""
    return _token


def _headers() -> dict:
    tok = _token or _get_token()
    return {"Authorization": f"Bearer {tok}"} if tok else {}


def _normalize(text: str) -> str:
    text = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        text = text.replace(a, b)
    return re.sub(r"[^\w\s]", " ", text)


def _field_covered_by_texts(field_name: str, doc_texts: list[str]) -> bool:
    """
    Compara el nombre del campo contra el texto real de cada documento.
    Extrae palabras significativas del nombre del campo (>3 chars) y verifica
    si al menos la mitad aparecen en el texto del documento.
    Esto es dinámico: funciona para cualquier campo sin hardcodear categorías.
    """
    field_n = _normalize(field_name)
    # Palabras significativas del nombre del campo (más de 3 caracteres)
    field_words = [w for w in field_n.split() if len(w) > 3]
    if not field_words:
        return False

    needed = max(1, len(field_words) // 2)  # al menos la mitad de las palabras

    for doc_text in doc_texts:
        if not doc_text.strip():
            continue
        doc_n = _normalize(doc_text)
        matched = sum(1 for w in field_words if w in doc_n)
        if matched >= needed:
            return True
    return False


class WorkflowMatcher:
    def __init__(self, db=None):
        self.workflows: list[dict] = []
        self.field_requirements: dict[str, dict] = {}  # wfId → {"required": [...], "optional": [...]}
        self.vectorizer = None
        self.encoder    = None
        self.wf_embeddings: list[np.ndarray] = []

        _get_token()
        self._load_workflows()

        if self.workflows:
            self._build_encoder()
            logger.info(f"WorkflowMatcher listo — {len(self.workflows)} workflows.")
        else:
            logger.warning("WorkflowMatcher: sin workflows.")

    # ------------------------------------------------------------------ #
    def _load_workflows(self):
        try:
            r = requests.get(f"{SPRING_API}/workflows", headers=_headers(), timeout=10)
            if r.status_code == 401:
                _get_token()
                r = requests.get(f"{SPRING_API}/workflows", headers=_headers(), timeout=10)
            r.raise_for_status()
            raw = r.json()
            if isinstance(raw, str):
                raw = json.loads(raw)
            self.workflows = [
                {"id": w.get("id",""), "name": w.get("name","Workflow"), "description": w.get("description","")}
                for w in (raw if isinstance(raw, list) else [])
                if w.get("id") and w.get("name")
            ]
            logger.info(f"Workflows cargados desde API: {len(self.workflows)}")
        except Exception as e:
            logger.warning(f"No se pudieron cargar workflows: {e}")
            self.workflows = []
            return

        # Para cada workflow, cargar nodos + fields del primer nodo con formulario
        for w in self.workflows:
            self._load_first_nodo_fields(w["id"])

        logger.info(f"Requisitos cargados: {len(self.workflows)} workflows configurados.")

    def _load_first_nodo_fields(self, wf_id: str):
        """Carga los campos FILE del primer nodo del workflow directamente desde Spring Boot."""
        try:
            r = requests.get(f"{SPRING_API}/workflows/{wf_id}", headers=_headers(), timeout=10)
            if r.status_code == 401:
                _get_token()
                r = requests.get(f"{SPRING_API}/workflows/{wf_id}", headers=_headers(), timeout=10)
            r.raise_for_status()
            full = r.json()

            nodos = full.get("nodo", [])
            nodos_sorted = sorted(nodos, key=lambda n: n.get("order", 9999))

            required: list[str] = []
            optional: list[str] = []

            for nodo in nodos_sorted:
                # Saltar nodo de inicio — no tiene formulario real
                if (nodo.get("nodeType") or "").lower() in ("inicio", "start", "fin", "end"):
                    continue
                fd = nodo.get("formDefinition")
                if not fd:
                    continue
                fields = fd.get("fields") or []
                file_fields = [f for f in fields if (f.get("type") or "").upper() == "FILE"]
                if not file_fields:
                    continue
                required = [f["name"] for f in file_fields if f.get("required") or f.get("isRequired")]
                optional = [f["name"] for f in file_fields if not (f.get("required") or f.get("isRequired"))]
                break  # Solo primer proceso con campos FILE

            self.field_requirements[wf_id] = {"required": required, "optional": optional}
            logger.debug(f"  {wf_id}: required={required}, optional={optional}")

        except Exception as e:
            logger.warning(f"No se pudieron cargar campos del workflow {wf_id}: {e}")
            self.field_requirements[wf_id] = {"required": [], "optional": []}

    def _build_encoder(self):
        texts = [self._wf_text(w) for w in self.workflows]
        texts = [t if t.strip() else "workflow" for t in texts]

        self.vectorizer = keras.layers.TextVectorization(
            max_tokens=600,
            output_sequence_length=30,
        )
        self.vectorizer.adapt(texts)
        vocab_size = len(self.vectorizer.get_vocabulary())

        inp = keras.Input(shape=(30,), dtype=tf.int32)
        x   = keras.layers.Embedding(vocab_size + 1, 32, mask_zero=True)(inp)
        out = keras.layers.GlobalAveragePooling1D()(x)
        self.encoder = keras.Model(inp, out)

        self.wf_embeddings = [self._encode(self._wf_text(w)) for w in self.workflows]

    # ------------------------------------------------------------------ #
    # Matching principal: texto del usuario + textos extraídos de los docs
    # ------------------------------------------------------------------ #
    def match_with_doc_texts(self, user_text: str,
                              doc_texts: list[str],
                              all_text:  str = "") -> list[dict]:
        """
        doc_texts: texto extraído de cada archivo subido (vacío para imágenes).
        Compara el contenido real del documento contra el nombre del campo
        en vez de usar categorías fijas — funciona para cualquier workflow.
        """
        if not self.workflows or self.encoder is None:
            return []

        query_emb = self._encode(_normalize(all_text or user_text))

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

            present_req = [f for f in required_fields if _field_covered_by_texts(f, doc_texts)]
            missing_req = [f for f in required_fields if f not in present_req]
            present_opt = [f for f in optional_fields if _field_covered_by_texts(f, doc_texts)]

            doc_score = (len(present_req) / len(required_fields)
                         if required_fields else 0.0)

            total = min(1.0, 0.60 * max(0, cos) + 0.40 * doc_score)

            results.append({
                "workflowId":      wid,
                "workflowName":    w["name"],
                "score":           round(total * 100, 1),
                "cosSim":          round(max(0, cos) * 100, 1),
                "confidence":      self._confidence(total),
                "requiredDocs":    required_fields,
                "optionalDocs":    optional_fields,
                "presentRequired": present_req,
                "missingRequired": missing_req,
                "presentOptional": present_opt,
                "docsComplete":    len(missing_req) == 0 and len(required_fields) > 0,
            })

        return sorted(results, key=lambda x: x["score"], reverse=True)[:6]

    # Mantener alias para compatibilidad con endpoint /nlp/match (solo texto)
    def match_with_doc_types(self, user_text: str,
                              doc_types: list[str],
                              all_text:  str = "") -> list[dict]:
        return self.match_with_doc_texts(user_text, [], all_text)

    def match(self, user_text: str, user_docs: list[str] | None = None) -> list[dict]:
        """Matching simple sin archivos (solo texto)."""
        if not self.workflows or self.encoder is None:
            return []

        user_emb = self._encode(_normalize(user_text))
        results  = []
        for i, w in enumerate(self.workflows):
            wid   = w["id"]
            w_emb = self.wf_embeddings[i]
            cos   = float(np.dot(user_emb, w_emb)
                          / (np.linalg.norm(user_emb) * np.linalg.norm(w_emb) + 1e-8))

            req            = self.field_requirements.get(wid, {})
            required_fields = req.get("required", [])
            optional_fields = req.get("optional", [])

            total = min(1.0, max(0, cos))

            results.append({
                "workflowId":      wid,
                "workflowName":    w["name"],
                "score":           round(total * 100, 1),
                "cosSim":          round(max(0, cos) * 100, 1),
                "confidence":      self._confidence(total),
                "requiredDocs":    required_fields,
                "optionalDocs":    optional_fields,
                "presentRequired": [],
                "missingRequired": required_fields,
                "presentOptional": [],
                "docsComplete":    False,
            })

        return sorted(results, key=lambda x: x["score"], reverse=True)[:6]

    # ------------------------------------------------------------------ #
    def _wf_text(self, w: dict) -> str:
        req   = self.field_requirements.get(w["id"], {})
        parts = [w["name"], w.get("description", "")]
        # Agregar nombres de campos requeridos como contexto semántico
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
