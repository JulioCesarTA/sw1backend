"""
RoutingEngine — 4 modelos TensorFlow entrenados con datos reales de la BD.

Models:
  1. RouteOptimizer    — Dense: predice calidad de ruta y mejor departamento
  2. RiskPredictor     — LSTM: predice riesgo de demora por secuencia de nodos
  3. AnomalyDetector   — Autoencoder: detecta patrones anómalos en trámites
  4. PriorityRanker    — Dense: rankea trámites por urgencia/prioridad

Todos los modelos se entrenan con datos reales obtenidos desde Spring Boot API.
"""

import logging
import os
import requests
from datetime import datetime, timezone

import numpy as np
import tensorflow as tf
from tensorflow import keras

logger = logging.getLogger(__name__)

SPRING_API     = os.getenv("SPRING_API_URL", "http://localhost:8080/api")
SPRING_EMAIL   = os.getenv("SPRING_EMAIL",   "juan@gmail.com")
SPRING_PASSWORD= os.getenv("SPRING_PASSWORD","julioavila")

MAX_SEQ = 8   # máximo de nodos en secuencia para LSTM


# ───────────────────────────────────────────────────────────────────────────
# Auth helper
# ───────────────────────────────────────────────────────────────────────────
_token: str = ""

def _get_token() -> str:
    global _token
    try:
        r = requests.post(f"{SPRING_API}/auth/login",
                          json={"email": SPRING_EMAIL, "password": SPRING_PASSWORD},
                          timeout=8)
        r.raise_for_status()
        d = r.json()
        _token = d.get("accessToken") or d.get("token") or ""
    except Exception as e:
        logger.warning(f"RoutingEngine: no se pudo obtener token: {e}")
        _token = ""
    return _token

def _headers() -> dict:
    tok = _token or _get_token()
    return {"Authorization": f"Bearer {tok}"} if tok else {}

def _get(path: str) -> list | dict:
    r = requests.get(f"{SPRING_API}{path}", headers=_headers(), timeout=15)
    if r.status_code == 401:
        _get_token()
        r = requests.get(f"{SPRING_API}{path}", headers=_headers(), timeout=15)
    r.raise_for_status()
    return r.json()


# ───────────────────────────────────────────────────────────────────────────
# Data loader — extrae features reales de la BD
# ───────────────────────────────────────────────────────────────────────────
class RealDataLoader:
    """Carga y transforma datos reales desde Spring Boot para entrenar los modelos."""

    def __init__(self):
        self.tramites:    list[dict] = []
        self.workflows:   dict[str, dict] = {}   # wfId → {name, nodos, est_hours}
        self.departments: list[str] = []
        self.dept_index:  dict[str, int] = {}

        self._load()

    def _load(self):
        _get_token()
        self._load_workflows()
        self._load_tramites()
        self._extract_departments()
        logger.info(
            f"RealDataLoader: {len(self.tramites)} trámites, "
            f"{len(self.workflows)} workflows, "
            f"{len(self.departments)} departamentos."
        )

    def _load_workflows(self):
        try:
            wfs = _get("/workflows")
            for wf in (wfs if isinstance(wfs, list) else []):
                wf_id = wf.get("id")
                if not wf_id:
                    continue
                try:
                    full  = _get(f"/workflows/{wf_id}")
                    nodos = full.get("nodo", [])
                    nodos_proc = [
                        n for n in nodos
                        if (n.get("nodeType") or "").lower() not in ("inicio","fin","start","end")
                    ]
                    # Duración estimada total en horas
                    est_minutes = sum(n.get("avgMinutes") or 0 for n in nodos_proc)
                    est_hours   = max(est_minutes / 60, 0.5)

                    # Depts de los nodos proceso
                    dept_names = [
                        n.get("responsibleDepartmentName") or ""
                        for n in nodos_proc
                        if n.get("responsibleDepartmentName")
                    ]

                    self.workflows[wf_id] = {
                        "name":      wf.get("name", ""),
                        "nodos":     nodos,
                        "nodos_proc": nodos_proc,
                        "num_nodos": len(nodos_proc),
                        "est_hours": est_hours,
                        "dept_names": dept_names,
                    }
                except Exception as e:
                    logger.debug(f"  workflow {wf_id} skip: {e}")
        except Exception as e:
            logger.warning(f"No se pudieron cargar workflows: {e}")

    def _load_tramites(self):
        try:
            rows = _get("/tramites/report-data")
            self.tramites = rows if isinstance(rows, list) else []
        except Exception as e:
            logger.warning(f"No se pudieron cargar trámites: {e}")
            self.tramites = []

    def _extract_departments(self):
        depts = set()
        for wf in self.workflows.values():
            for d in wf.get("dept_names", []):
                if d:
                    depts.add(d)
        for t in self.tramites:
            d = t.get("departmentName") or ""
            if d:
                depts.add(d)
        self.departments = sorted(depts) or ["Sin departamento"]
        self.dept_index  = {d: i for i, d in enumerate(self.departments)}

    # ── Helpers ─────────────────────────────────────────────────────────── #

    def _hours_since(self, iso_str: str) -> float:
        """Horas transcurridas desde una fecha ISO hasta ahora."""
        try:
            dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
            now = datetime.now(timezone.utc)
            return max(0.0, (now - dt).total_seconds() / 3600)
        except Exception:
            return 24.0

    def _dept_enc(self, dept_name: str) -> float:
        n = len(self.departments)
        if n <= 1:
            return 0.5
        return self.dept_index.get(dept_name, 0) / (n - 1)

    def _node_type_enc(self, node_type: str) -> float:
        return {"inicio": 0.0, "proceso": 0.5, "decision": 0.75, "fin": 1.0}.get(
            (node_type or "").lower(), 0.5
        )

    def _status_quality(self, status: str) -> float:
        return {"COMPLETADO": 1.0, "EN_PROGRESO": 0.6, "PENDIENTE": 0.4, "RECHAZADO": 0.1}.get(
            (status or "").upper(), 0.5
        )

    # ── Feature builders ────────────────────────────────────────────────── #

    def build_route_data(self) -> tuple[np.ndarray, np.ndarray, int]:
        """
        X: [complexity, num_nodos_norm, dept_load_ratio, time_of_day, est_hours_norm]
        y: [quality, dept_one_hot...]
        """
        num_depts = len(self.departments)
        rows_X, rows_y = [], []

        # Calcular carga por departamento (cuántos trámites EN_PROGRESO hay por dept)
        dept_active: dict[str, int] = {}
        total_active = 0
        for t in self.tramites:
            if (t.get("status") or "").upper() == "EN_PROGRESO":
                d = t.get("departmentName") or "Sin departamento"
                dept_active[d] = dept_active.get(d, 0) + 1
                total_active  += 1

        max_active = max(dept_active.values(), default=1)

        for t in self.tramites:
            wf_id   = t.get("workflowId") or ""
            wf_info = self.workflows.get(wf_id, {})
            dept    = t.get("departmentName") or (
                self.departments[0] if self.departments else "Sin departamento"
            )

            num_proc  = wf_info.get("num_nodos", 3)
            est_hours = wf_info.get("est_hours", 8.0)
            complexity = np.clip((num_proc - 1) / 9, 0, 1)   # 1-10 nodos → 0-1

            dept_load = dept_active.get(dept, 0) / max(max_active, 1)

            created_at = t.get("createdAt") or ""
            time_of_day = 0.5
            try:
                h = datetime.fromisoformat(created_at.replace("Z", "+00:00")).hour
                time_of_day = h / 23
            except Exception:
                pass

            quality   = self._status_quality(t.get("status", ""))
            dept_idx  = self.dept_index.get(dept, 0)
            dept_oh   = np.eye(num_depts, dtype=np.float32)[dept_idx]

            rows_X.append([
                complexity,
                np.clip((num_proc - 1) / 9, 0, 1),
                dept_load,
                time_of_day,
                np.clip(est_hours / 24, 0, 1),
            ])
            rows_y.append(np.concatenate([[quality], dept_oh]))

        X = np.array(rows_X, dtype=np.float32)
        y = np.array(rows_y, dtype=np.float32)
        return self._augment(X), self._augment(y, noise=0.02), num_depts

    def build_risk_sequences(self) -> tuple[np.ndarray, np.ndarray]:
        """
        Construye secuencias de nodos por trámite para el LSTM.
        X: (N, MAX_SEQ, 3) → [time_ratio, node_type_enc, dept_enc]
        y: (N, 2) → [risk_score, bottleneck_norm]
        """
        seqs_X, seqs_y = [], []

        for t in self.tramites:
            wf_id   = t.get("workflowId") or ""
            wf_info = self.workflows.get(wf_id, {})
            nodos   = wf_info.get("nodos_proc", [])
            if not nodos:
                continue

            status     = (t.get("status") or "").upper()
            created_at = t.get("createdAt") or ""
            elapsed_h  = self._hours_since(created_at)
            est_hours  = wf_info.get("est_hours", 8.0)

            # overall time ratio para este trámite
            overall_ratio = np.clip(elapsed_h / max(est_hours, 0.5), 0.1, 4.0)

            # Generar la secuencia nodo por nodo
            seq = []
            for n in nodos[:MAX_SEQ]:
                avg_min    = max(n.get("avgMinutes") or 30, 1)
                # Distribuir el ratio general entre los nodos
                node_ratio = overall_ratio * (1 + np.random.normal(0, 0.15))
                node_ratio = np.clip(node_ratio, 0.1, 4.0)

                dept_name  = n.get("responsibleDepartmentName") or ""
                dept_enc   = self._dept_enc(dept_name)
                type_enc   = self._node_type_enc(n.get("nodeType", "proceso"))

                seq.append([float(node_ratio), float(type_enc), float(dept_enc)])

            # Padding
            while len(seq) < MAX_SEQ:
                seq.append([1.0, 0.5, 0.5])

            # risk = proporción de nodos por encima de 1.5× el tiempo esperado
            time_ratios  = [s[0] for s in seq[:len(nodos)]]
            over          = sum(1 for r in time_ratios if r > 1.5) / max(len(time_ratios), 1)
            # Penalizar RECHAZADO y EN_PROGRESO muy demorado
            if status == "RECHAZADO":
                over = max(over, 0.7)
            elif status == "EN_PROGRESO" and overall_ratio > 1.5:
                over = max(over, 0.5)
            elif status == "COMPLETADO":
                over = min(over, 0.3)

            bottleneck_idx = int(np.argmax(time_ratios))
            bottleneck_norm = bottleneck_idx / max(len(time_ratios) - 1, 1)

            seqs_X.append(seq)
            seqs_y.append([float(np.clip(over, 0, 1)), float(bottleneck_norm)])

        X = np.array(seqs_X, dtype=np.float32)
        y = np.array(seqs_y, dtype=np.float32)
        return self._augment_seq(X), self._augment(y, noise=0.02)

    def build_anomaly_data(self) -> tuple[np.ndarray, dict]:
        """
        8 features por trámite para el Autoencoder.
        Solo trámites COMPLETADOS como "normales" + augment.
        """
        rows = []

        for t in self.tramites:
            wf_id    = t.get("workflowId") or ""
            wf_info  = self.workflows.get(wf_id, {})
            status   = (t.get("status") or "").upper()

            elapsed_h = self._hours_since(t.get("createdAt") or "")
            num_nodos = max(wf_info.get("num_nodos", 3), 1)
            est_hours = max(wf_info.get("est_hours", 8.0), 0.5)
            avg_time  = elapsed_h / num_nodos

            # cambios de dept: distintos depts en los nodos del workflow
            dept_names = wf_info.get("dept_names", [])
            dept_sw    = max(len(set(dept_names)) - 1, 0)

            completion   = self._status_quality(status)
            overtime     = np.clip(elapsed_h / est_hours - 1.0, 0, 3.0)

            created_at = t.get("createdAt") or ""
            weekend = 0.0
            try:
                dt = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
                weekend = 1.0 if dt.weekday() >= 5 else 0.0
            except Exception:
                pass

            rows.append([
                elapsed_h,       # total_duration_hours
                float(num_nodos),# num_nodes
                avg_time,        # avg_time_per_node
                float(dept_sw),  # dept_switches
                completion,      # completion_rate
                overtime,        # overtime_ratio
                weekend,         # weekend_activity
                0.0,             # reassignment_count (sin historial)
            ])

        if not rows:
            # fallback mínimo
            rows = [[8.0, 4.0, 2.0, 1.0, 0.8, 0.1, 0.0, 0.0]]

        X_raw = np.array(rows, dtype=np.float32)

        # Stats reales para normalización
        stats = {
            "total_dur":  (float(X_raw[:, 0].min()), float(X_raw[:, 0].max()) + 1e-6),
            "num_nodes":  (1.0, 20.0),
            "avg_time":   (0.0, float(X_raw[:, 2].max()) + 1e-6),
            "dept_sw":    (0.0, max(float(X_raw[:, 3].max()), 1.0)),
            "completion": (0.0, 1.0),
            "overtime":   (0.0, max(float(X_raw[:, 5].max()), 0.5)),
            "weekend":    (0.0, 1.0),
            "reassign":   (0.0, 5.0),
        }

        X_aug = self._augment(X_raw, copies=8, noise=0.08)
        return X_aug, stats

    def build_priority_data(self) -> np.ndarray:
        """
        Features para PriorityRanker.
        [wait_hours_norm, risk_norm, complexity_norm, sla_ratio, pending_norm, dept_overload]
        """
        rows = []
        num_depts = max(len(self.departments), 1)

        # Carga por dept
        dept_count: dict[str, int] = {}
        for t in self.tramites:
            d = t.get("departmentName") or ""
            dept_count[d] = dept_count.get(d, 0) + 1
        max_dept_count = max(dept_count.values(), default=1)

        for t in self.tramites:
            wf_id   = t.get("workflowId") or ""
            wf_info = self.workflows.get(wf_id, {})
            dept    = t.get("departmentName") or ""
            status  = (t.get("status") or "").upper()

            wait_h    = self._hours_since(t.get("createdAt") or "")
            est_h     = max(wf_info.get("est_hours", 8.0), 0.5)
            num_nodos = max(wf_info.get("num_nodos", 3), 1)

            # risk proxy: overtime
            overtime = np.clip(wait_h / est_h - 1.0, 0, 3.0) / 3.0
            if status == "RECHAZADO":
                overtime = max(overtime, 0.7)

            complexity    = np.clip((num_nodos - 1) / 9, 0, 1)
            sla_remaining = np.clip(1.0 - wait_h / max(est_h * 1.5, 1), 0, 1)
            dept_overload = dept_count.get(dept, 0) / max_dept_count

            rows.append([
                np.clip(wait_h / 168, 0, 1),   # normalizado a 1 semana
                overtime,
                complexity,
                sla_remaining,
                0.0,                            # pending deps (sin historial)
                dept_overload,
            ])

        if not rows:
            rows = [[0.5, 0.3, 0.5, 0.5, 0.0, 0.3]]

        X = np.array(rows, dtype=np.float32)
        return self._augment(X, copies=6, noise=0.05)

    # ── Augmentation ────────────────────────────────────────────────────── #

    def _augment(self, X: np.ndarray, copies: int = 10, noise: float = 0.05) -> np.ndarray:
        """Genera variantes con ruido gaussiano para ampliar datos reales escasos."""
        if len(X) == 0:
            return X
        parts = [X]
        for _ in range(copies):
            noisy = X + np.random.normal(0, noise, X.shape)
            noisy = np.clip(noisy, 0, 1)
            parts.append(noisy.astype(np.float32))
        return np.vstack(parts)

    def _augment_seq(self, X: np.ndarray, copies: int = 10, noise: float = 0.05) -> np.ndarray:
        """Augment para secuencias 3D (N, MAX_SEQ, 3)."""
        if len(X) == 0:
            return X
        parts = [X]
        for _ in range(copies):
            noisy = X + np.random.normal(0, noise, X.shape)
            noisy = np.clip(noisy, 0, 4.0)
            parts.append(noisy.astype(np.float32))
        return np.vstack(parts)


# ───────────────────────────────────────────────────────────────────────────
# RoutingEngine
# ───────────────────────────────────────────────────────────────────────────
class RoutingEngine:

    def __init__(self):
        logger.info("RoutingEngine: cargando datos reales de la BD …")
        self._loader = RealDataLoader()

        self.DEPARTMENTS = self._loader.departments
        self.dept_index  = self._loader.dept_index
        self.workflows   = self._loader.workflows

        logger.info(f"  Departamentos reales: {self.DEPARTMENTS}")
        logger.info("RoutingEngine: iniciando entrenamiento de 4 modelos …")

        self._train_route_optimizer()
        self._train_risk_predictor()
        self._train_anomaly_detector()
        self._train_priority_ranker()

        logger.info("RoutingEngine: todos los modelos entrenados y listos.")

    # ── Model 1: RouteOptimizer ─────────────────────────────────────────── #
    def _train_route_optimizer(self):
        logger.info("  [1/4] Entrenando RouteOptimizer con datos reales …")
        X, y, num_depts = self._loader.build_route_data()

        if len(X) < 5:
            logger.warning("    Muy pocos datos — RouteOptimizer con mínimo sintético.")
            X, y, num_depts = self._minimal_route_data()

        inp  = keras.Input(shape=(5,))
        h    = keras.layers.Dense(64, activation="relu")(inp)
        h    = keras.layers.Dropout(0.1)(h)
        h    = keras.layers.Dense(32, activation="relu")(h)
        out  = keras.layers.Dense(1 + num_depts, activation="sigmoid")(h)

        self._route_model = keras.Model(inp, out)
        self._route_model.compile(optimizer="adam", loss="mse")
        self._route_model.fit(X, y, epochs=80, batch_size=16, verbose=0)
        self._num_depts = num_depts
        logger.info("    RouteOptimizer ✓")

    def _minimal_route_data(self):
        nd = max(len(self.DEPARTMENTS), 1)
        N  = 100
        X  = np.random.rand(N, 5).astype(np.float32)
        q  = (1 - X[:, 0] * 0.5 - X[:, 2] * 0.3).reshape(-1, 1)
        di = (X[:, 0] * (nd - 1)).astype(int) % nd
        oh = np.eye(nd, dtype=np.float32)[di]
        return X, np.hstack([q, oh]).astype(np.float32), nd

    # ── Model 2: RiskPredictor ──────────────────────────────────────────── #
    def _train_risk_predictor(self):
        logger.info("  [2/4] Entrenando RiskPredictor (LSTM) con datos reales …")
        X, y = self._loader.build_risk_sequences()

        if len(X) < 5:
            logger.warning("    Muy pocos datos — RiskPredictor con mínimo sintético.")
            X, y = self._minimal_risk_data()

        inp = keras.Input(shape=(MAX_SEQ, 3))
        h   = keras.layers.LSTM(64, return_sequences=True)(inp)
        h   = keras.layers.LSTM(32)(h)
        out = keras.layers.Dense(2, activation="sigmoid")(h)

        self._risk_model = keras.Model(inp, out)
        self._risk_model.compile(optimizer="adam", loss="mse")
        self._risk_model.fit(X, y, epochs=50, batch_size=16, verbose=0)
        logger.info("    RiskPredictor ✓")

    def _minimal_risk_data(self):
        N  = 100
        X  = np.random.uniform(0.5, 2.0, (N, MAX_SEQ, 3)).astype(np.float32)
        tr = X[:, :, 0]
        rs = np.clip((tr > 1.5).sum(axis=1) / MAX_SEQ, 0, 1)
        bi = tr.argmax(axis=1) / (MAX_SEQ - 1)
        return X, np.column_stack([rs, bi]).astype(np.float32)

    # ── Model 3: AnomalyDetector ────────────────────────────────────────── #
    def _train_anomaly_detector(self):
        logger.info("  [3/4] Entrenando AnomalyDetector (Autoencoder) con datos reales …")
        X_raw, stats = self._loader.build_anomaly_data()
        self._anomaly_stats = stats

        def _norm(X):
            keys = ["total_dur","num_nodes","avg_time","dept_sw",
                    "completion","overtime","weekend","reassign"]
            cols = []
            for i, k in enumerate(keys):
                lo, hi = stats[k]
                cols.append(np.clip((X[:, i] - lo) / (hi - lo + 1e-8), 0, 1))
            return np.column_stack(cols).astype(np.float32)

        self._normalize_anomaly = _norm
        X = _norm(X_raw)

        inp  = keras.Input(shape=(8,))
        enc  = keras.layers.Dense(4, activation="relu")(inp)
        bot  = keras.layers.Dense(2, activation="relu")(enc)
        dec  = keras.layers.Dense(4, activation="relu")(bot)
        out  = keras.layers.Dense(8, activation="sigmoid")(dec)

        self._anomaly_model = keras.Model(inp, out)
        self._anomaly_model.compile(optimizer="adam", loss="mse")
        self._anomaly_model.fit(X, X, epochs=80, batch_size=16, verbose=0)

        recon  = self._anomaly_model.predict(X, verbose=0)
        errors = np.mean(np.square(X - recon), axis=1)
        self._anomaly_threshold  = float(errors.mean() + 2 * errors.std())
        self._anomaly_error_mean = float(errors.mean())
        logger.info(f"    AnomalyDetector ✓  threshold={self._anomaly_threshold:.4f}")

    # ── Model 4: PriorityRanker ─────────────────────────────────────────── #
    def _train_priority_ranker(self):
        logger.info("  [4/4] Entrenando PriorityRanker con datos reales …")
        X = self._loader.build_priority_data()

        # Target: combinación ponderada de las features (ground truth proxy)
        priority = (
            0.35 * X[:, 0] +   # wait
            0.30 * X[:, 1] +   # risk/overtime
            0.25 * (1 - X[:, 3]) +  # SLA agotado
            0.10 * X[:, 5]     # dept overload
        )
        priority = np.clip(priority + np.random.normal(0, 0.02, len(priority)), 0, 1)

        inp = keras.Input(shape=(6,))
        h   = keras.layers.Dense(32, activation="relu")(inp)
        h   = keras.layers.Dropout(0.1)(h)
        h   = keras.layers.Dense(16, activation="relu")(h)
        out = keras.layers.Dense(1, activation="sigmoid")(h)

        self._priority_model = keras.Model(inp, out)
        self._priority_model.compile(optimizer="adam", loss="mse")
        self._priority_model.fit(X, priority.astype(np.float32), epochs=60, batch_size=16, verbose=0)
        logger.info("    PriorityRanker ✓")

    # ───────────────────────────────────────────────────────────────────────
    # Inference
    # ───────────────────────────────────────────────────────────────────────

    def predict_route(self, workflow_complexity, num_nodes, dept_load_ratio,
                      time_of_day, estimated_duration_hours) -> dict:
        x = np.array([[
            np.clip(workflow_complexity, 0, 1),
            np.clip((num_nodes - 1) / 9, 0, 1),
            np.clip(dept_load_ratio, 0, 1),
            np.clip(time_of_day, 0, 1),
            np.clip(estimated_duration_hours / 24, 0, 1),
        ]], dtype=np.float32)

        pred = self._route_model.predict(x, verbose=0)[0]
        quality    = float(pred[0])
        dept_probs = pred[1:1 + self._num_depts]
        best_idx   = int(np.argmax(dept_probs))
        confidence = float(dept_probs[best_idx])

        sorted_idx   = np.argsort(dept_probs)[::-1]
        alternatives = [
            {"dept": self.DEPARTMENTS[i], "score": round(float(dept_probs[i]), 3)}
            for i in sorted_idx[1:3]
            if i < len(self.DEPARTMENTS)
        ]

        return {
            "recommended_dept":     self.DEPARTMENTS[best_idx] if best_idx < len(self.DEPARTMENTS) else "—",
            "recommended_dept_idx": best_idx,
            "route_quality_score":  round(quality, 4),
            "confidence":           round(confidence, 4),
            "alternatives":         alternatives,
        }

    def predict_risk(self, node_history: list) -> dict:
        seq = []
        for h in node_history[:MAX_SEQ]:
            seq.append([
                float(h.get("time_ratio", 1.0)),
                float(h.get("node_type_encoded", 0.5)),
                float(h.get("dept_encoded", 0.5)),
            ])
        while len(seq) < MAX_SEQ:
            seq.append([1.0, 0.5, 0.5])

        X    = np.array([seq], dtype=np.float32)
        pred = self._risk_model.predict(X, verbose=0)[0]

        risk_score     = float(pred[0])
        bottleneck_idx = round(float(pred[1]) * (MAX_SEQ - 1))
        bottleneck_idx = max(0, min(bottleneck_idx, len(node_history) - 1))

        return {"risk_score": round(risk_score, 4), "bottleneck_idx": bottleneck_idx}

    def detect_anomaly(self, features: np.ndarray) -> dict:
        X_raw  = features.reshape(1, -1)
        X_norm = self._normalize_anomaly(X_raw)
        recon  = self._anomaly_model.predict(X_norm, verbose=0)
        error  = float(np.mean(np.square(X_norm - recon)))

        anomaly_score = min(1.0, error / (self._anomaly_threshold * 2))
        is_anomaly    = error > self._anomaly_threshold

        feat_errors  = np.square(X_norm[0] - recon[0])
        worst_idx    = int(np.argmax(feat_errors))
        feat_names   = [
            "total_duration", "num_nodes", "avg_time_per_node", "dept_switches",
            "completion_rate", "overtime_ratio", "weekend_activity", "reassignment_count",
        ]

        return {
            "anomaly_score":        round(anomaly_score, 4),
            "is_anomaly":           bool(is_anomaly),
            "anomaly_type":         feat_names[worst_idx] if is_anomaly else "none",
            "reconstruction_error": round(error, 6),
            "threshold":            round(self._anomaly_threshold, 6),
        }

    def rank_priority(self, tramites: list) -> list:
        if not tramites:
            return []

        rows = []
        for t in tramites:
            wait_h    = float(t.get("wait_hours", 0))
            risk      = float(t.get("risk_score", 0))
            complexity= float(t.get("workflow_complexity", 0.5))
            sla_rem_h = float(t.get("sla_remaining_hours", 24))
            total_sla = float(t.get("total_sla_hours", 72))
            sla_ratio = sla_rem_h / max(total_sla, 1)
            overload  = float(t.get("dept_overload_score", 0))

            rows.append([
                np.clip(wait_h / 168, 0, 1),
                np.clip(risk, 0, 1),
                np.clip(complexity, 0, 1),
                np.clip(sla_ratio, 0, 1),
                0.0,
                np.clip(overload, 0, 1),
            ])

        X     = np.array(rows, dtype=np.float32)
        preds = self._priority_model.predict(X, verbose=0).flatten() * 100

        results = []
        for i, t in enumerate(tramites):
            results.append({**t, "priority_score": round(float(preds[i]), 2)})
        return results

    def dept_encoded(self, dept_name: str) -> float:
        n = len(self.DEPARTMENTS)
        if n <= 1:
            return 0.5
        return self.dept_index.get(dept_name, 0) / (n - 1)
