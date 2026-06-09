import logging
import numpy as np
from datetime import datetime
from core.api_client import get_mongo_db, load_workflows
from ai.predictor.time_utils import naive_dt as _naive_dt

logger = logging.getLogger(__name__)


class DelayPredictor:
    """
    Predice la probabilidad de que un nuevo trámite de un workflow se demore
    más de un 30% sobre el tiempo esperado.

    Features (3):
      expected_hours_norm  — complejidad del workflow (minutos esperados / 480)
      num_nodos_norm       — cantidad de nodos / 10
      historical_delay_rate — tasa histórica de demora de ese workflow [0,1]

    Entrenado con trámites COMPLETADOS/RECHAZADOS reales.
    Si no hay datos suficientes, no entrena modelo y usa solo hist_rate.
    """

    def __init__(self):
        import tensorflow as tf
        self._tf = tf
        self._wf_map, _ = load_workflows()
        db = get_mongo_db()
        self._model, self._wf_delay_rate = self._train(db)
        logger.info("DelayPredictor listo")

    # ── Entrenamiento ────────────────────────────────────────────────────────

    def _train(self, db):
        tramites = list(db["tramites"].find(
            {"status": {"$in": ["COMPLETADO", "RECHAZADO"]}},
            {"_id": 1, "workflowId": 1, "createdAt": 1},
        ))

        # Duración real desde timestamps del historial
        tramite_ids = [str(t["_id"]) for t in tramites]
        all_hist    = list(db["historial_tramites"].find(
            {"tramiteId": {"$in": tramite_ids}},
            {"tramiteId": 1, "changedAt": 1},
        ))

        hist_by_t: dict = {}
        for h in all_hist:
            hist_by_t.setdefault(h["tramiteId"], []).append(h)

        duration_per_t: dict = {}
        for tid, records in hist_by_t.items():
            timestamps = [_naive_dt(r["changedAt"]) for r in records]
            if len(timestamps) >= 2:
                duration_per_t[tid] = (max(timestamps) - min(timestamps)).total_seconds() / 60

        X, y = [], []
        wf_delays: dict = {}

        for t in tramites:
            tid = str(t["_id"])
            wid = t.get("workflowId", "")
            if wid not in self._wf_map:
                continue
            actual = duration_per_t.get(tid, 0.0)
            if actual == 0:
                continue

            wf      = self._wf_map[wid]
            exp_min = wf["total_expected_min"]
            delayed = 1 if actual > exp_min * 1.3 else 0
            wf_delays.setdefault(wid, []).append(delayed)

            X.append([
                min(exp_min / 480.0, 1.0),
                min(wf["num_nodos"] / 10.0, 1.0),
                sum(wf_delays.get(wid, [0])) / max(len(wf_delays.get(wid, [1])), 1),
            ])
            y.append(float(delayed))

        delay_rate = {wid: sum(v) / len(v) for wid, v in wf_delays.items()}

        if len(X) < 5:
            logger.warning("DelayPredictor: datos insuficientes, se usará solo hist_rate")
            return None, delay_rate

        X_np = np.array(X, dtype=np.float32)
        y_np = np.array(y, dtype=np.float32)
        noise = np.random.normal(0, 0.05, (len(X_np) * 8, 3)).astype(np.float32)
        X_np  = np.vstack([X_np, np.clip(np.tile(X_np, (8, 1)) + noise, 0, 1)])
        y_np  = np.concatenate([y_np, np.tile(y_np, 8)])

        model = self._tf.keras.Sequential([
            self._tf.keras.layers.Dense(16, activation="relu", input_shape=(3,)),
            self._tf.keras.layers.Dropout(0.2),
            self._tf.keras.layers.Dense(8, activation="relu"),
            self._tf.keras.layers.Dense(1, activation="sigmoid"),
        ])
        model.compile(optimizer="adam", loss="binary_crossentropy")
        model.fit(X_np, y_np, epochs=40, batch_size=16, verbose=0)
        logger.info(f"DelayPredictor entrenado con {len(X)} trámites reales")
        return model, delay_rate

    # ── Inferencia ───────────────────────────────────────────────────────────

    def predict(self, workflow_id: str) -> dict:
        if workflow_id not in self._wf_map:
            raise ValueError(f"Workflow {workflow_id} no encontrado")

        wf        = self._wf_map[workflow_id]
        hist_rate = self._wf_delay_rate.get(workflow_id, None)

        if self._model is None or hist_rate is None:
            # Sin datos históricos reales: no podemos predecir
            prob = hist_rate if hist_rate is not None else None
        else:
            feat = np.array([[
                min(wf["total_expected_min"] / 480.0, 1.0),
                min(wf["num_nodos"] / 10.0, 1.0),
                hist_rate,
            ]], dtype=np.float32)
            prob_tf = float(self._model.predict(feat, verbose=0)[0][0])
            prob    = round(0.6 * prob_tf + 0.4 * hist_rate, 3)

        if prob is None:
            return {
                "workflowId":   workflow_id,
                "workflowName": wf["name"],
                "available":    False,
                "message":      "Sin historial suficiente para predecir",
            }

        extra_h = round(wf["total_expected_min"] / 60 * max(0.0, prob - 0.5) * 2, 1) if prob > 0.5 else 0.0
        level   = "ALTO" if prob >= 0.7 else "MEDIO" if prob >= 0.4 else "BAJO"

        return {
            "workflowId":          workflow_id,
            "workflowName":        wf["name"],
            "available":           True,
            "delayProbability":    round(prob, 3),
            "riskLevel":           level,
            "extraHoursEstimated": extra_h,
            "totalExpectedHours":  round(wf["total_expected_min"] / 60, 1),
            "numNodos":            wf["num_nodos"],
            "historicalDelayRate": round(hist_rate, 3),
        }
