import logging
import numpy as np
from datetime import datetime
from core.api_client import get_mongo_db, load_workflows
from ai.predictor.time_utils import naive_dt as _naive_dt

logger = logging.getLogger(__name__)


class AnomalyDetector:
    """
    Autoencoder TensorFlow por workflow, entrenado con los trámites
    normales (completados dentro de tiempo esperado ×1.3) de ese workflow.

    Arquitectura por modelo:
        Input(4) → Dense(8,relu) → Dense(3,relu)
                 → Dense(8,relu) → Dense(4,sigmoid)

    Features (4):
      elapsed_ratio       — minutos_transcurridos / minutos_esperados  [0,1]
      nodo_position_ratio — posición del nodo actual / total nodos     [0,1]
      time_in_nodo_ratio  — minutos en nodo actual / avgMinutes        [0,1]
      wf_load             — trámites activos mismo workflow / 8        [0,1]
    """

    FEATURE_NAMES = [
        "elapsed_ratio", "nodo_position_ratio", "time_in_nodo_ratio", "wf_load",
    ]
    N = 4

    @staticmethod
    def _fmt_h(h: float) -> str:
        if h >= 24: return f"{h/24:.1f} días"
        if h >= 1:  return f"{h:.1f} h"
        return f"{round(h*60)} min"

    def _factor_detail(self, main_factor: str, elapsed_h: float, exp_h: float,
                       time_in_nodo_min: float, avg_nodo_min: float, nodo_pos: float) -> str:
        fmt = self._fmt_h
        if main_factor == "elapsed_ratio":
            ratio = elapsed_h / max(exp_h, 0.01)
            return f"Lleva {fmt(elapsed_h)} abierto · esperado {fmt(exp_h)} → {ratio:.1f}× el tiempo normal"
        if main_factor == "time_in_nodo_ratio":
            time_h = time_in_nodo_min / 60
            avg_h  = avg_nodo_min / 60
            ratio  = time_h / max(avg_h, 0.01)
            return f"Lleva {fmt(time_h)} en el nodo actual · promedio {fmt(avg_h)} → {ratio:.1f}× lo normal"
        if main_factor == "nodo_position_ratio":
            return f"Posición en el flujo inusual ({nodo_pos*100:.0f}% del recorrido)"
        if main_factor == "wf_load":
            return "Carga del workflow inusualmente alta"
        return main_factor

    def __init__(self):
        import tensorflow as tf
        self._tf = tf
        self._wf_map, self._nodo_map = load_workflows()
        self._db = get_mongo_db()
        self._models: dict = {}
        self._train_all()
        logger.info(f"AnomalyDetector listo — modelos: {len(self._models)}")

    # ── Entrenamiento ────────────────────────────────────────────────────────

    def _train_all(self):
        """Entrena un autoencoder por workflow usando datos reales del historial."""
        tramites = list(self._db["tramites"].find(
            {"status": {"$in": ["COMPLETADO", "RECHAZADO"]}},
            {"_id": 1, "workflowId": 1, "createdAt": 1},
        ))

        # Calcular duración real de cada trámite desde los timestamps de historial
        tramite_ids = [str(t["_id"]) for t in tramites]
        all_hist = list(self._db["historial_tramites"].find(
            {"tramiteId": {"$in": tramite_ids}},
            {"tramiteId": 1, "changedAt": 1, "toNodoId": 1},
        ))

        # Agrupar historial por trámite
        hist_by_t: dict = {}
        for h in all_hist:
            hist_by_t.setdefault(h["tramiteId"], []).append(h)

        # Duración total en minutos por trámite (last - first changedAt)
        duration_per_t: dict = {}
        for tid, records in hist_by_t.items():
            timestamps = [_naive_dt(r["changedAt"]) for r in records]
            if len(timestamps) >= 2:
                span = (max(timestamps) - min(timestamps)).total_seconds() / 60
                duration_per_t[tid] = span

        # Agrupar trámites normales por workflow
        by_wf: dict = {}
        for t in tramites:
            wid = t.get("workflowId", "")
            if wid not in self._wf_map:
                continue
            tid    = str(t["_id"])
            actual = duration_per_t.get(tid, 0.0)
            if actual == 0:
                continue
            wf     = self._wf_map[wid]
            exp_min = max(wf["total_expected_min"], 1)
            if actual > exp_min * 1.3:
                continue  # descartamos anómalos del entrenamiento
            by_wf.setdefault(wid, []).append((t, actual, hist_by_t.get(tid, [])))

        for wid, samples in by_wf.items():
            wf = self._wf_map[wid]
            self._models[wid] = self._build_and_train(wid, wf, samples)

    def _build_and_train(self, wid: str, wf: dict, samples: list):
        if not samples:
            raise ValueError(f"No hay datos reales para entrenar [{wf['name']}]")

        exp_min = max(wf["total_expected_min"], 1)
        X = []
        for t, actual_min, hist_records in samples:
            created     = _naive_dt(t.get("createdAt", datetime.now()))
            hist_sorted = sorted(hist_records, key=lambda h: _naive_dt(h["changedAt"]))

            for i, h in enumerate(hist_sorted):
                step_time          = _naive_dt(h["changedAt"])
                elapsed_at_step    = max(0.0, (step_time - created).total_seconds() / 60)
                step_elapsed_ratio = min(elapsed_at_step / exp_min, 1.0)

                nodo_info = self._nodo_map.get(h.get("toNodoId"))
                nodo_pos  = float(nodo_info["order"] / max(wf["num_nodos"] - 1, 1)) if nodo_info else (i / max(len(hist_sorted) - 1, 1))

                if i > 0:
                    prev_time        = _naive_dt(hist_sorted[i - 1]["changedAt"])
                    time_in_nodo_min = max(0.0, (step_time - prev_time).total_seconds() / 60)
                else:
                    time_in_nodo_min = elapsed_at_step

                avg_nodo_min  = (nodo_info["avgMinutes"] if nodo_info and nodo_info.get("avgMinutes")
                                 else exp_min / max(wf["num_nodos"], 1))
                time_in_ratio = min(time_in_nodo_min / max(avg_nodo_min, 1), 1.0)
                X.append([step_elapsed_ratio, nodo_pos, time_in_ratio, 0.3])

            X_np  = np.array(X, dtype=np.float32)
            noise = np.random.normal(0, 0.03, (len(X_np) * 5, self.N)).astype(np.float32)
            X_np  = np.vstack([X_np, np.clip(np.tile(X_np, (5, 1)) + noise, 0.0, 1.0)])

        inp        = self._tf.keras.Input(shape=(self.N,))
        encoded    = self._tf.keras.layers.Dense(8, activation="relu")(inp)
        bottleneck = self._tf.keras.layers.Dense(3, activation="relu")(encoded)
        decoded    = self._tf.keras.layers.Dense(8, activation="relu")(bottleneck)
        out        = self._tf.keras.layers.Dense(self.N, activation="sigmoid")(decoded)
        model      = self._tf.keras.Model(inp, out)
        model.compile(optimizer="adam", loss="mse")
        model.fit(X_np, X_np, epochs=50, batch_size=16, verbose=0)

        recon     = model.predict(X_np, verbose=0)
        errors    = np.mean(np.square(X_np - recon), axis=1)
        threshold = float(np.percentile(errors, 95))

        logger.info(f"  AnomalyDetector [{wf['name']}]: entrenado con {len(samples)} trámites reales — threshold={threshold:.4f}")
        return (model, threshold)

    # ── Inferencia ───────────────────────────────────────────────────────────

    def _build_features(self, tramites_act: list, wid: str, now: datetime) -> tuple:
        """Construye X_batch y meta para un conjunto de trámites activos."""
        wf       = self._wf_map.get(wid)
        exp_min  = max(wf["total_expected_min"], 1)
        wf_load  = min(len(tramites_act) / 8.0, 1.0)

        tramite_ids = [str(t["_id"]) for t in tramites_act]
        last_hist: dict = {}
        for h in self._db["historial_tramites"].find(
            {"tramiteId": {"$in": tramite_ids}},
            {"tramiteId": 1, "changedAt": 1},
        ).sort("changedAt", -1):
            tid = h["tramiteId"]
            if tid not in last_hist:
                last_hist[tid] = h

        X_batch, meta = [], []
        for t in tramites_act:
            created = _naive_dt(t.get("createdAt", now))
            try:
                elapsed_h = max(0.0, (now - created).total_seconds() / 3600)
            except TypeError:
                elapsed_h = 0.0

            elapsed_ratio = min(elapsed_h * 60 / exp_min, 3.0) / 3.0

            cur_nodo_id = t.get("currentNodoId")
            nodo_info   = self._nodo_map.get(cur_nodo_id) if cur_nodo_id else None
            nodo_pos    = float(nodo_info["order"] / max(wf["num_nodos"] - 1, 1)) if nodo_info else 0.5

            tid = str(t["_id"])
            lh  = last_hist.get(tid)
            if lh:
                changed = _naive_dt(lh["changedAt"])
                try:
                    time_in_nodo_min = max(0.0, (now - changed).total_seconds() / 60)
                except TypeError:
                    time_in_nodo_min = elapsed_h * 60
            else:
                time_in_nodo_min = elapsed_h * 60

            avg_nodo_min       = (nodo_info["avgMinutes"] if nodo_info and nodo_info.get("avgMinutes")
                                  else exp_min / max(wf["num_nodos"], 1))
            time_in_nodo_ratio = min(time_in_nodo_min / max(avg_nodo_min, 1), 3.0) / 3.0

            features = [elapsed_ratio, nodo_pos, time_in_nodo_ratio, wf_load]
            X_batch.append(features)
            meta.append((t, elapsed_h, exp_min / 60.0, features, time_in_nodo_min, avg_nodo_min, nodo_pos))

        return np.array(X_batch, dtype=np.float32), meta

    def _score_entries(self, X_np, meta, model, threshold) -> tuple:
        """Clasifica trámites en anomalías y normales dado el modelo."""
        recon  = model.predict(X_np, verbose=0)
        errors = np.mean(np.square(X_np - recon), axis=1)

        anomalies, normal = [], []
        for (t, elapsed_h, exp_h, features, time_in_nodo_min, avg_nodo_min, nodo_pos), error, feat_recon in zip(meta, errors, recon):
            error      = float(error)
            is_anomaly = error > threshold

            feat_errors = np.square(np.array(features, dtype=np.float32) - feat_recon)
            top_idx     = int(np.argmax(feat_errors))
            main_factor = self.FEATURE_NAMES[top_idx]
            score       = round(min(error / max(threshold * 2, 1e-8), 1.0), 3)

            entry = {
                "id":           str(t["_id"]),
                "code":         t.get("code", ""),
                "title":        t.get("title", ""),
                "workflowName": self._wf_map.get(t.get("workflowId", ""), {}).get("name", ""),
                "status":       t.get("status", ""),
                "elapsedHours": round(elapsed_h, 1),
                "expectedHours": round(exp_h, 1),
                "anomalyScore": score,
                "isAnomaly":    is_anomaly,
                "mainFactor":   main_factor,
                "factorDetail": self._factor_detail(main_factor, elapsed_h, exp_h, time_in_nodo_min, avg_nodo_min, nodo_pos),
            }
            (anomalies if is_anomaly else normal).append(entry)

        return anomalies, normal

    def detect(self) -> dict:
        tramites = list(self._db["tramites"].find(
            {"status": {"$in": ["PENDIENTE", "EN_PROGRESO"]}},
            {"_id": 1, "code": 1, "title": 1, "workflowId": 1,
             "currentNodoId": 1, "createdAt": 1, "status": 1},
        ))
        if not tramites:
            return {"total": 0, "totalAnomalies": 0, "anomalies": [], "normal": []}

        now = datetime.now()
        by_wf: dict = {}
        for t in tramites:
            by_wf.setdefault(t.get("workflowId", ""), []).append(t)

        all_anomalies, all_normal = [], []
        for wid, wf_tramites in by_wf.items():
            entry_model = self._models.get(wid)
            if not self._wf_map.get(wid) or not entry_model:
                continue
            model, threshold = entry_model
            X_np, meta = self._build_features(wf_tramites, wid, now)
            anomalies, normal = self._score_entries(X_np, meta, model, threshold)
            all_anomalies.extend(anomalies)
            all_normal.extend(normal)

        all_anomalies.sort(key=lambda x: x["anomalyScore"], reverse=True)
        return {
            "total":          len(all_anomalies) + len(all_normal),
            "totalAnomalies": len(all_anomalies),
            "anomalies":      all_anomalies,
            "normal":         all_normal,
        }

    def train_and_detect_workflow(self, workflow_id: str) -> dict:
        """Re-entrena el autoencoder para el workflow indicado y evalúa sus trámites activos."""
        if workflow_id not in self._wf_map:
            raise ValueError(f"Workflow {workflow_id} no encontrado")

        wf      = self._wf_map[workflow_id]
        exp_min = max(wf["total_expected_min"], 1)

        tramites_comp = list(self._db["tramites"].find(
            {"workflowId": workflow_id, "status": {"$in": ["COMPLETADO", "RECHAZADO"]}},
            {"_id": 1, "workflowId": 1, "createdAt": 1},
        ))

        tramite_ids = [str(t["_id"]) for t in tramites_comp]
        all_hist = list(self._db["historial_tramites"].find(
            {"tramiteId": {"$in": tramite_ids}},
            {"tramiteId": 1, "changedAt": 1, "toNodoId": 1},
        ))

        hist_by_t: dict = {}
        for h in all_hist:
            hist_by_t.setdefault(h["tramiteId"], []).append(h)

        duration_per_t: dict = {}
        for tid, records in hist_by_t.items():
            timestamps = [_naive_dt(r["changedAt"]) for r in records]
            if len(timestamps) >= 2:
                duration_per_t[tid] = (max(timestamps) - min(timestamps)).total_seconds() / 60

        samples = []
        for t in tramites_comp:
            tid    = str(t["_id"])
            actual = duration_per_t.get(tid, 0.0)
            if actual == 0 or actual > exp_min * 1.3:
                continue
            samples.append((t, actual, hist_by_t.get(tid, [])))

        model, threshold = self._build_and_train(workflow_id, wf, samples)
        self._models[workflow_id] = (model, threshold)
        logger.info(f"AnomalyDetector re-entrenado para [{wf['name']}] — {len(samples)} muestras normales")

        tramites_act = list(self._db["tramites"].find(
            {"workflowId": workflow_id, "status": {"$in": ["PENDIENTE", "EN_PROGRESO"]}},
            {"_id": 1, "code": 1, "title": 1, "workflowId": 1,
             "currentNodoId": 1, "createdAt": 1, "status": 1},
        ))
        if not tramites_act:
            return {
                "workflowId":     workflow_id,
                "workflowName":   wf["name"],
                "trainedOn":      len(samples),
                "total":          0,
                "totalAnomalies": 0,
                "anomalies":      [],
                "normal":         [],
            }

        now = datetime.now()
        X_np, meta = self._build_features(tramites_act, workflow_id, now)
        anomalies, normal = self._score_entries(X_np, meta, model, threshold)
        anomalies.sort(key=lambda x: x["anomalyScore"], reverse=True)

        return {
            "workflowId":     workflow_id,
            "workflowName":   wf["name"],
            "trainedOn":      len(samples),
            "total":          len(anomalies) + len(normal),
            "totalAnomalies": len(anomalies),
            "anomalies":      anomalies,
            "normal":         normal,
        }
