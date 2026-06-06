"""
predictor_service.py
Three IA models:
  - DelayPredictor      : probability a new tramite will be delayed
  - BottleneckPredictor : which nodes are likely bottlenecks
  - PriorityRanker      : rank active tramites by urgency (avgMinutes = SLA proxy)
"""
import os, logging
import numpy as np
import requests
from datetime import datetime
from dotenv import load_dotenv
import pymongo

load_dotenv()

MONGO_URI    = os.getenv("MONGODB_URI", "")
SPRING_API   = os.getenv("SPRING_API_URL", "http://localhost:8080/api")
SPRING_EMAIL = os.getenv("SPRING_EMAIL", "juan@gmail.com")
SPRING_PASS  = os.getenv("SPRING_PASSWORD", "julioavila")

logger = logging.getLogger(__name__)


def _spring_headers() -> dict:
    try:
        r = requests.post(
            f"{SPRING_API}/auth/login",
            json={"email": SPRING_EMAIL, "password": SPRING_PASS},
            timeout=5,
        )
        token = r.json().get("accessToken", "")
        return {"Authorization": f"Bearer {token}"}
    except Exception as e:
        logger.error(f"Spring Boot auth failed: {e}")
        return {}


def _load_wf_data(headers: dict) -> tuple:
    """
    Returns (wf_map, nodo_map):
      wf_map  : {wfId -> {name, nodos, num_nodos, total_expected_min}}
      nodo_map: {nodoId -> {avgMinutes, order, total_nodos, wfId, name}}
    """
    try:
        wf_list = requests.get(f"{SPRING_API}/workflows", headers=headers, timeout=10).json()
    except Exception as e:
        logger.error(f"Could not load workflows: {e}")
        return {}, {}

    wf_map, nodo_map = {}, {}

    for wf in wf_list:
        wid = wf["id"]
        try:
            full = requests.get(f"{SPRING_API}/workflows/{wid}", headers=headers, timeout=10).json()
        except Exception:
            continue

        all_nodos = sorted(full.get("nodo", []), key=lambda n: n.get("order", 999))
        nodos = [n for n in all_nodos
                 if (n.get("nodeType") or "").lower() not in ("inicio", "fin", "start", "end")]

        total_min = sum(n.get("avgMinutes") or 30 for n in nodos)
        wf_map[wid] = {
            "name":               wf.get("name", ""),
            "nodos":              nodos,
            "num_nodos":          len(nodos),
            "total_expected_min": max(total_min, 1),
        }

        for i, n in enumerate(nodos):
            nodo_map[n["id"]] = {
                "avgMinutes":  n.get("avgMinutes") or 30,
                "order":       i,
                "total_nodos": len(nodos),
                "wfId":        wid,
                "name":        n.get("name", "Nodo"),
            }

    logger.info(f"Workflows: {len(wf_map)}, nodos: {len(nodo_map)}")
    return wf_map, nodo_map


def _naive_dt(dt) -> datetime:
    """Strip timezone info if present."""
    if isinstance(dt, str):
        try:
            dt = datetime.fromisoformat(dt.replace("Z", ""))
        except Exception:
            return datetime.now()
    if hasattr(dt, "tzinfo") and dt.tzinfo is not None:
        dt = dt.replace(tzinfo=None)
    return dt if isinstance(dt, datetime) else datetime.now()


# ─────────────────────────────────────────────────────────────────────────────
# Model 1 — DelayPredictor
# ─────────────────────────────────────────────────────────────────────────────

class DelayPredictor:
    """
    Dense neural network that predicts the probability a new tramite
    for a given workflow will exceed its expected time by ≥ 30 %.

    Features (5):
      expected_hours_norm, num_nodos_norm, hour_of_day, day_of_week, dept_load
    Label:
      1 if actual_total_duration > expected_total * 1.3 else 0
    """

    def __init__(self):
        import tensorflow as tf
        self._tf = tf

        headers = _spring_headers()
        self._wf_map, _ = _load_wf_data(headers)

        client = pymongo.MongoClient(MONGO_URI)
        db = client["workflow_db"]
        self._model, self._wf_delay_rate = self._train(db)
        client.close()
        logger.info("DelayPredictor ready")

    # ── training ──────────────────────────────────────────────────────────

    def _train(self, db):
        tramites = list(db["tramites"].find(
            {"status": {"$in": ["COMPLETADO", "RECHAZADO"]}},
            {"_id": 1, "workflowId": 1, "createdAt": 1},
        ))
        hist = list(db["historial_tramites"].find(
            {"durationInNodo": {"$ne": None}},
            {"tramiteId": 1, "durationInNodo": 1},
        ))

        actual_per_t: dict = {}
        for h in hist:
            tid = h["tramiteId"]
            actual_per_t[tid] = actual_per_t.get(tid, 0.0) + (h.get("durationInNodo") or 0)

        wf_counts: dict = {}
        for t in tramites:
            wid = t.get("workflowId", "")
            wf_counts[wid] = wf_counts.get(wid, 0) + 1
        max_c = max(wf_counts.values(), default=1)

        X, y = [], []
        wf_delays: dict = {}

        for t in tramites:
            tid = str(t["_id"])
            wid = t.get("workflowId", "")
            if wid not in self._wf_map:
                continue
            actual = actual_per_t.get(tid, 0.0)
            if actual == 0:
                continue

            wf      = self._wf_map[wid]
            exp_min = wf["total_expected_min"]
            delayed = 1 if actual > exp_min * 1.3 else 0

            created = _naive_dt(t.get("createdAt", datetime.now()))
            wf_delays.setdefault(wid, []).append(delayed)

            X.append([
                min(exp_min / 480.0, 1.0),
                min(wf["num_nodos"] / 10.0, 1.0),
                created.hour / 23.0,
                created.weekday() / 6.0,
                min(wf_counts.get(wid, 0) / max_c, 1.0),
            ])
            y.append(float(delayed))

        delay_rate = {wid: sum(v) / len(v) for wid, v in wf_delays.items()}

        if len(X) < 5:
            logger.warning("DelayPredictor: insufficient data, using synthetic")
            X_np = np.random.rand(100, 5).astype(np.float32)
            y_np = (X_np[:, 0] > 0.6).astype(np.float32)
        else:
            X_np = np.array(X, dtype=np.float32)
            y_np = np.array(y, dtype=np.float32)
            noise = np.random.normal(0, 0.05, (len(X_np) * 8, 5)).astype(np.float32)
            X_np = np.vstack([X_np, np.clip(np.tile(X_np, (8, 1)) + noise, 0, 1)])
            y_np = np.concatenate([y_np, np.tile(y_np, 8)])

        model = self._tf.keras.Sequential([
            self._tf.keras.layers.Dense(32, activation="relu", input_shape=(5,)),
            self._tf.keras.layers.Dropout(0.2),
            self._tf.keras.layers.Dense(16, activation="relu"),
            self._tf.keras.layers.Dense(1, activation="sigmoid"),
        ])
        model.compile(optimizer="adam", loss="binary_crossentropy")
        model.fit(X_np, y_np, epochs=30, batch_size=32, verbose=0)
        logger.info(f"DelayPredictor trained on {len(X_np)} samples")
        return model, delay_rate

    # ── inference ─────────────────────────────────────────────────────────

    def predict(self, workflow_id: str) -> dict:
        if workflow_id not in self._wf_map:
            raise ValueError(f"Workflow {workflow_id} not found")

        wf  = self._wf_map[workflow_id]
        now = datetime.now()

        feat = np.array([[
            min(wf["total_expected_min"] / 480.0, 1.0),
            min(wf["num_nodos"] / 10.0, 1.0),
            now.hour / 23.0,
            now.weekday() / 6.0,
            0.5,
        ]], dtype=np.float32)

        prob_tf   = float(self._model.predict(feat, verbose=0)[0][0])
        hist_rate = self._wf_delay_rate.get(workflow_id, 0.35)
        prob      = round(0.6 * prob_tf + 0.4 * hist_rate, 3)

        extra_h = round(wf["total_expected_min"] / 60 * max(0.0, prob - 0.5) * 2, 1) if prob > 0.5 else 0.0
        level   = "ALTO" if prob >= 0.7 else "MEDIO" if prob >= 0.4 else "BAJO"

        days = ["Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"]
        return {
            "workflowId":          workflow_id,
            "workflowName":        wf["name"],
            "delayProbability":    prob,
            "riskLevel":           level,
            "extraHoursEstimated": extra_h,
            "totalExpectedHours":  round(wf["total_expected_min"] / 60, 1),
            "numNodos":            wf["num_nodos"],
            "historicalDelayRate": round(hist_rate, 3),
            "context": {
                "hour":    now.hour,
                "dayName": days[now.weekday()],
            },
        }


# ─────────────────────────────────────────────────────────────────────────────
# Model 2 — BottleneckPredictor
# ─────────────────────────────────────────────────────────────────────────────

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

        headers = _spring_headers()
        self._wf_map, self._nodo_map = _load_wf_data(headers)

        client = pymongo.MongoClient(MONGO_URI)
        db = client["workflow_db"]
        self._model, self._node_overtime, self._node_visits = self._train(db)
        client.close()
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


# ─────────────────────────────────────────────────────────────────────────────
# Model 3 — PriorityRanker
# ─────────────────────────────────────────────────────────────────────────────

class PriorityRanker:
    """
    Ranks all active tramites (PENDIENTE / EN_PROGRESO) by urgency.

    SLA proxy  = sum(node.avgMinutes) for each workflow node  (total expected hours)
    Base ratio = elapsed_hours / expected_hours
    Dept bonus = small penalty when many concurrent tramites in the same workflow
    Final ratio → CRITICAL ≥ 1.5 | HIGH ≥ 1.0 | MEDIUM ≥ 0.5 | LOW < 0.5
    """

    def __init__(self):
        headers = _spring_headers()
        self._wf_map, _ = _load_wf_data(headers)
        self._db = pymongo.MongoClient(MONGO_URI)["workflow_db"]
        logger.info(f"PriorityRanker ready. Workflows: {len(self._wf_map)}")

    def rank(self) -> list:
        tramites = list(self._db["tramites"].find(
            {"status": {"$in": ["PENDIENTE", "EN_PROGRESO"]}},
            {"_id": 1, "code": 1, "title": 1, "workflowId": 1, "createdAt": 1, "status": 1},
        ))

        now = datetime.now()

        wf_active: dict = {}
        for t in tramites:
            wid = t.get("workflowId", "")
            wf_active[wid] = wf_active.get(wid, 0) + 1

        result = []
        for t in tramites:
            wid = t.get("workflowId", "")
            wf  = self._wf_map.get(wid)
            if not wf:
                continue

            created   = _naive_dt(t.get("createdAt", now))
            try:
                elapsed_h = max(0.0, (now - created).total_seconds() / 3600)
            except TypeError:
                elapsed_h = 0.0

            expected_h = wf["total_expected_min"] / 60
            base_ratio = elapsed_h / max(expected_h, 0.5)
            dept_bonus = min(wf_active.get(wid, 0) / 8.0, 0.25)
            ratio      = base_ratio + dept_bonus

            level = ("CRITICAL" if ratio >= 1.5 else
                     "HIGH"     if ratio >= 1.0 else
                     "MEDIUM"   if ratio >= 0.5 else "LOW")

            result.append({
                "id":            str(t["_id"]),
                "code":          t.get("code", str(t["_id"])[:8]),
                "title":         t.get("title", ""),
                "workflowName":  wf["name"],
                "status":        t.get("status", ""),
                "elapsedHours":  round(elapsed_h, 1),
                "expectedHours": round(expected_h, 1),
                "ratio":         round(ratio, 2),
                "urgencyLevel":  level,
            })

        result.sort(key=lambda x: x["ratio"], reverse=True)
        for i, r in enumerate(result):
            r["rank"] = i + 1

        return result
