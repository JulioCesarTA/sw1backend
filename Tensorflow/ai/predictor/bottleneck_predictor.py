import logging
import numpy as np
from datetime import datetime
from core.api_client import get_mongo_db, load_workflows
from ai.predictor.time_utils import naive_dt as _naive_dt

logger = logging.getLogger(__name__)

class BottleneckPredictor:
    """
    Dense neural network per-node: predicts probability each node will be a bottleneck.

    Features (4):
      avg_minutes_norm, position_in_workflow, historical_overtime_ratio, visit_norm
    Label:
      1 if durationInNodo > avgMinutes * 1.5 else 0
    """

    def __init__(self):
        import tensorflow as tf
        self._tf = tf

        self._wf_map, self._nodo_map = load_workflows()

        db = get_mongo_db()
        self._model, self._node_overtime, self._node_visits = self._train(db)
        logger.info("BottleneckPredictor ready")

    # ── training ──────────────────────────────────────────────────────────

    def _train(self, db):
        hist = list(db["historial_tramites"].find(
            {"durationInNodo": {"$ne": None}},
            {"toNodoId": 1, "durationInNodo": 1},
        ))

        node_durs: dict = {}
        for h in hist:
            nid = h.get("toNodoId")
            dur = h.get("durationInNodo")
            if nid and dur is not None:
                node_durs.setdefault(nid, []).append(float(dur))

        node_overtime: dict = {}
        node_visits:   dict = {}
        for nid, durs in node_durs.items():
            if nid not in self._nodo_map:
                continue
            avg_m = self._nodo_map[nid]["avgMinutes"]
            node_overtime[nid] = float(np.mean([d / max(avg_m, 1) for d in durs]))
            node_visits[nid]   = len(durs)

        X, y = [], []
        for nid, durs in node_durs.items():
            if nid not in self._nodo_map:
                continue
            info   = self._nodo_map[nid]
            avg_m  = info["avgMinutes"]
            tot_n  = max(info["total_nodos"], 1)
            avg_ot = node_overtime.get(nid, 1.0)
            n_vis  = node_visits.get(nid, 0)

            for dur in durs:
                X.append([
                    min(avg_m / 120.0, 1.0),
                    info["order"] / tot_n,
                    min(avg_ot / 3.0, 1.0),
                    min(n_vis / 50.0, 1.0),
                ])
                y.append(1.0 if dur / max(avg_m, 1) > 1.5 else 0.0)

        if len(X) < 5:
            logger.warning("BottleneckPredictor: insufficient data, using synthetic")
            X_np = np.random.rand(80, 4).astype(np.float32)
            y_np = (X_np[:, 2] > 0.5).astype(np.float32)
        else:
            X_np = np.array(X, dtype=np.float32)
            y_np = np.array(y, dtype=np.float32)
            noise = np.random.normal(0, 0.05, (len(X_np) * 5, 4)).astype(np.float32)
            X_np = np.vstack([X_np, np.clip(np.tile(X_np, (5, 1)) + noise, 0, 1)])
            y_np = np.concatenate([y_np, np.tile(y_np, 5)])

        model = self._tf.keras.Sequential([
            self._tf.keras.layers.Dense(24, activation="relu", input_shape=(4,)),
            self._tf.keras.layers.Dropout(0.2),
            self._tf.keras.layers.Dense(12, activation="relu"),
            self._tf.keras.layers.Dense(1, activation="sigmoid"),
        ])
        model.compile(optimizer="adam", loss="binary_crossentropy")
        model.fit(X_np, y_np, epochs=30, batch_size=16, verbose=0)
        logger.info(f"BottleneckPredictor trained on {len(X_np)} samples")
        return model, node_overtime, node_visits

    # ── inference ─────────────────────────────────────────────────────────

    def predict(self, workflow_id: str) -> dict:
        if workflow_id not in self._wf_map:
            raise ValueError(f"Workflow {workflow_id} not found")

        wf    = self._wf_map[workflow_id]
        nodos = wf["nodos"]
        tot_n = max(len(nodos), 1)

        nodes_out = []
        for i, n in enumerate(nodos):
            nid     = n["id"]
            avg_m   = n.get("avgMinutes") or 30
            hist_ot = self._node_overtime.get(nid, 1.0)
            n_vis   = self._node_visits.get(nid, 0)

            feat = np.array([[
                min(avg_m / 120.0, 1.0),
                i / tot_n,
                min(hist_ot / 3.0, 1.0),
                min(n_vis / 50.0, 1.0),
            ]], dtype=np.float32)

            prob = float(self._model.predict(feat, verbose=0)[0][0])

            if hist_ot >= 2.0:
                prob = min(1.0, prob + 0.25)
            elif hist_ot >= 1.5:
                prob = min(1.0, prob + 0.1)

            prob = round(prob, 3)
            risk = ("CRITICO" if prob >= 0.7 else
                    "ALTO"    if prob >= 0.45 else
                    "MEDIO"   if prob >= 0.25 else "BAJO")

            nodes_out.append({
                "nodoId":             nid,
                "nodoName":           n.get("name", "Nodo"),
                "avgMinutes":         avg_m,
                "bottleneckProb":     prob,
                "riskLevel":          risk,
                "historicalOvertime": round(hist_ot, 2),
                "visits":             n_vis,
            })

        nodes_out.sort(key=lambda x: x["bottleneckProb"], reverse=True)
        top = nodes_out[0] if nodes_out else None

        summary = (
            f"Nodo con mayor riesgo: '{top['nodoName']}' — "
            f"{round(top['bottleneckProb'] * 100)}% probabilidad de cuello de botella. "
            f"Históricamente demora {top['historicalOvertime']}x el tiempo esperado."
            if top else "Sin datos suficientes."
        )

        return {
            "workflowId":    workflow_id,
            "workflowName":  wf["name"],
            "nodes":         nodes_out,
            "topBottleneck": top,
            "summary":       summary,
        }

