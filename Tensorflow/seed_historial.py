"""
seed_historial.py
Genera historial_tramites realista para los tramites existentes.
Lee la estructura real de nodos del workflow desde la API y simula
el camino que tomó cada trámite con tiempos variables.
"""
import os, random, requests
from datetime import datetime, timedelta
from bson import ObjectId
from dotenv import load_dotenv
import pymongo

load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), "../.env"))

MONGO_URI       = os.getenv("MONGODB_URI", "")
SPRING_API      = "http://localhost:8080/api"
SPRING_EMAIL    = "juan@gmail.com"
SPRING_PASSWORD = "julioavila"

client = pymongo.MongoClient(MONGO_URI)
db     = client["workflow_db"]

# ── Auth ──────────────────────────────────────────────────────────────────
r = requests.post(f"{SPRING_API}/auth/login",
                  json={"email": SPRING_EMAIL, "password": SPRING_PASSWORD})
token   = r.json().get("accessToken", "")
headers = {"Authorization": f"Bearer {token}"}
print(f"Token: {token[:30]}...")

# ── Leer estructura de workflows ──────────────────────────────────────────
wf_list = requests.get(f"{SPRING_API}/workflows", headers=headers).json()

# wf_id → lista de nodos proceso ordenados (sin inicio/fin)
wf_nodes: dict[str, list] = {}
wf_inicio: dict[str, str] = {}   # wf_id → id del nodo inicio

for wf in wf_list:
    wf_id = wf["id"]
    full  = requests.get(f"{SPRING_API}/workflows/{wf_id}", headers=headers).json()
    nodos = sorted(full.get("nodo", []), key=lambda n: n.get("order", 9999))

    inicio = next((n for n in nodos if (n.get("nodeType") or "").lower() in ("inicio","start")), None)
    if inicio:
        wf_inicio[wf_id] = inicio["id"]

    proceso = [n for n in nodos if (n.get("nodeType") or "").lower()
               not in ("inicio","fin","start","end")]
    wf_nodes[wf_id] = proceso
    print(f"  Workflow '{wf['name']}': {len(proceso)} nodos proceso, inicio={wf_inicio.get(wf_id,'?')}")

# ── Leer tramites que no tienen historial ─────────────────────────────────
all_tramites = list(db["tramites"].find({}))
print(f"\nTotal trámites en BD: {len(all_tramites)}")

# IDs de tramites que ya tienen historial
with_hist = set(
    h["tramiteId"] for h in db["historial_tramites"].find({}, {"tramiteId": 1})
)
print(f"Trámites con historial existente: {len(with_hist)}")

to_seed = [t for t in all_tramites if str(t["_id"]) not in with_hist]
print(f"Trámites a generar historial: {len(to_seed)}")

# ── Generar historial ─────────────────────────────────────────────────────
inserted_total = 0
hist_col = db["historial_tramites"]

for tramite in to_seed:
    tramite_id = str(tramite["_id"])
    wf_id      = tramite.get("workflowId", "")
    status     = tramite.get("status", "EN_PROGRESO")
    created_at = tramite.get("createdAt", datetime.now())
    if isinstance(created_at, str):
        created_at = datetime.fromisoformat(created_at)

    nodos   = wf_nodes.get(wf_id, [])
    inicio  = wf_inicio.get(wf_id)

    if not nodos:
        continue

    # Decidir cuántos nodos recorrió según el estado
    if status == "COMPLETADO":
        n_recorridos = len(nodos)          # pasó todos
    elif status == "RECHAZADO":
        n_recorridos = random.randint(1, max(1, len(nodos) - 1))
    elif status == "EN_PROGRESO":
        n_recorridos = random.randint(1, max(1, len(nodos) - 1))
    else:  # PENDIENTE
        n_recorridos = 1

    nodos_recorridos = nodos[:n_recorridos]

    # Timestamp acumulado desde createdAt
    current_time = created_at

    # Entrada 1: CREADO → nodo inicio
    entries = []
    if inicio:
        entries.append({
            "_id":        ObjectId(),
            "tramiteId":  tramite_id,
            "fromNodoId": None,
            "toNodoId":   inicio,
            "action":     "CREADO",
            "changedById": str(tramite.get("requestedById", "")),
            "comment":    "Trámite creado",
            "changedAt":  current_time,
            "durationInNodo": None,
        })
        # Permanece en inicio entre 5 y 20 min
        current_time += timedelta(minutes=random.randint(5, 20))

    # Avances por cada nodo proceso
    prev_nodo_id = inicio
    for i, nodo in enumerate(nodos_recorridos):
        avg_min = nodo.get("avgMinutes") or 30

        # Simular variación: algunos nodos dentro del tiempo, otros con demora
        variation = random.choice([
            random.uniform(0.5, 1.0),   # más rápido que lo esperado
            random.uniform(0.8, 1.3),   # dentro del rango normal
            random.uniform(1.5, 3.5),   # cuello de botella (menos frecuente)
        ]) if random.random() < 0.25 else random.uniform(0.7, 1.4)

        real_min = max(5, int(avg_min * variation))
        current_time += timedelta(minutes=real_min)

        # Si es el último nodo y fue rechazado
        if i == len(nodos_recorridos) - 1 and status == "RECHAZADO":
            action = "RECHAZADO"
        else:
            action = "AVANZADO"

        entries.append({
            "_id":            ObjectId(),
            "tramiteId":      tramite_id,
            "fromNodoId":     prev_nodo_id,
            "toNodoId":       nodo["id"],
            "action":         action,
            "changedById":    str(tramite.get("assignedUserId", "")),
            "comment":        "",
            "changedAt":      current_time,
            "durationInNodo": real_min,
        })
        prev_nodo_id = nodo["id"]

    if entries:
        hist_col.insert_many(entries)
        inserted_total += len(entries)

print(f"\nHistorial generado: {inserted_total} entradas")
print(f"Total historial en BD: {hist_col.count_documents({})}")
client.close()
