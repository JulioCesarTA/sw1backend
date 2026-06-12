"""
model_exporter.py
Serializes Keras model weights to a JSON format loadable by TF.js in the browser.

Format:
  model_name.json  → {weights: [{name, shape, data: float32[]}], metadata: {...}}
"""

import json
import logging
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

STATIC_MODELS_DIR = Path(__file__).resolve().parent.parent / "static" / "models"


def export_weights(model, name: str, extra: dict = None) -> None:
    """Save model weights + optional metadata to static/models/<name>.json"""
    out = STATIC_MODELS_DIR / name
    out.mkdir(parents=True, exist_ok=True)

    weights = []
    for w in model.weights:
        arr = w.numpy().astype(np.float32)
        weights.append({
            "name": w.name,
            "shape": list(arr.shape),
            "data": arr.flatten().tolist(),
        })

    payload = {"weights": weights}
    if extra:
        payload.update(extra)

    target = out / "model.json"
    with open(target, "w", encoding="utf-8") as f:
        json.dump(payload, f, separators=(",", ":"))

    size_kb = target.stat().st_size / 1024
    logger.info(f"[export] {name} → {target} ({size_kb:.1f} KB)")
