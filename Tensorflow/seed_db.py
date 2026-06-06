"""
seed_db.py — Pobla la BD con tramites de prueba variados.
Correr una sola vez: python seed_db.py
"""
import os, random
from datetime import datetime, timedelta
from bson import ObjectId
from dotenv import load_dotenv

load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), "../.env"))

import pymongo

MONGO_URI = os.getenv("MONGODB_URI", "")
if not MONGO_URI:
    raise RuntimeError("MONGODB_URI no encontrado en .env")

client = pymongo.MongoClient(MONGO_URI)
db = client["workflow_db"]

# ─── IDs reales sacados del sistema ───────────────────────────────────────────
WORKFLOWS = [
    {"id": "6a238b7ca17766255bbb8d44",  "name": "software1"},
    {"id": "6a22ba99a17766255bbb8d3c",  "name": "Gestion de Contrato Comercial"},
    {"id": "6a22ba94a17766255bbb8d34",  "name": "Reclamo por Corte de Servicio o Fuga"},
    {"id": "6a22ba90a17766255bbb8d2c",  "name": "Solicitud de Nueva Conexion de Agua"},
]

# Nodos por workflow → determinan el departamento en el reporte
# Eligimos nodos con departamento asignado
NODOS = [
    # software1
    {"id": "6a238b80a17766255bbb8d46", "wfId": "6a238b7ca17766255bbb8d44", "dept": "Atencion al Cliente"},
    {"id": "6a238b84a17766255bbb8d48", "wfId": "6a238b7ca17766255bbb8d44", "dept": "Atencion al Cliente"},
    # Gestion de Contrato Comercial
    {"id": "6a22ba9aa17766255bbb8d3e", "wfId": "6a22ba99a17766255bbb8d3c", "dept": "Comercial y Facturacion"},
    {"id": "6a22ba9aa17766255bbb8d3f", "wfId": "6a22ba99a17766255bbb8d3c", "dept": "Legal y Contratos"},
    {"id": "6a22ba9ba17766255bbb8d41", "wfId": "6a22ba99a17766255bbb8d3c", "dept": "Gerencia General"},
    # Reclamo por Corte de Servicio
    {"id": "6a22ba95a17766255bbb8d36", "wfId": "6a22ba94a17766255bbb8d34", "dept": "Operaciones y Redes"},
    {"id": "6a22ba96a17766255bbb8d37", "wfId": "6a22ba94a17766255bbb8d34", "dept": "Operaciones y Redes"},
    {"id": "6a22ba97a17766255bbb8d39", "wfId": "6a22ba94a17766255bbb8d34", "dept": "Gerencia General"},
    # Solicitud de Nueva Conexion
    {"id": "6a22ba91a17766255bbb8d2e", "wfId": "6a22ba90a17766255bbb8d2c", "dept": "Atencion al Cliente"},
    {"id": "6a22ba92a17766255bbb8d30", "wfId": "6a22ba90a17766255bbb8d2c", "dept": "Operaciones y Redes"},
    {"id": "6a22ba93a17766255bbb8d31", "wfId": "6a22ba90a17766255bbb8d2c", "dept": "Comercial y Facturacion"},
]

USERS = [
    {"id": "69eebf93c7dee848ceb9237b", "name": "Juan"},
    {"id": "69f18ccc55898a0b09f319ff", "name": "carlos"},
    {"id": "69eec8deadd41a4b71a62694", "name": "Jouse"},
]

STATUSES = ["COMPLETADO", "COMPLETADO", "EN_PROGRESO", "PENDIENTE", "RECHAZADO"]

TITLES = [
    "Solicitud de soporte técnico nivel 1",
    "Revisión de contrato comercial",
    "Reclamo por corte de servicio",
    "Nueva conexión residencial",
    "Gestión de documentación pendiente",
    "Aprobación de presupuesto",
    "Actualización de datos del cliente",
    "Inspección técnica urgente",
    "Alta en sistema de facturación",
    "Revisión legal de cláusulas",
    "Solicitud de baja de servicio",
    "Verificación de identidad",
    "Reporte de fuga en sector norte",
    "Contrato de adhesión empresarial",
    "Instalación de medidor nuevo",
    "Reclamo por error en factura",
    "Renovación de contrato anual",
    "Derivación a gerencia",
    "Control de calidad operaciones",
    "Solicitud de extensión de red",
]

COMPANY_ID = "69eebf62c7dee848ceb9237a"

def rand_date(month: int, year: int = 2025) -> datetime:
    import calendar
    day = random.randint(1, calendar.monthrange(year, month)[1])
    return datetime(year, month, day, random.randint(8, 17), random.randint(0, 59))

def next_code(col) -> str:
    count = col.count_documents({}) + 1
    return f"TRM{count:05d}"

col = db["tramites"]
existing = col.count_documents({})
print(f"Tramites existentes: {existing}")

# Generar 30 tramites distribuidos en 6 meses (enero–junio 2025)
inserted = 0
for i in range(30):
    nodo = random.choice(NODOS)
    wf   = next(w for w in WORKFLOWS if w["id"] == nodo["wfId"])
    user = random.choice(USERS)
    status = random.choice(STATUSES)
    month  = random.randint(1, 6)
    created = rand_date(month)

    doc = {
        "_id":           ObjectId(),
        "code":          next_code(col),
        "title":         random.choice(TITLES),
        "description":   f"Trámite generado para pruebas de reporte — mes {month}",
        "workflowId":    wf["id"],
        "currentNodoId": nodo["id"],
        "requestedById": user["id"],
        "assignedUserId": user["id"],
        "status":        status,
        "companyId":     COMPANY_ID,
        "formData":      {},
        "createdAt":     created,
        "updatedAt":     created,
        "parentTramiteId": None,
    }
    col.insert_one(doc)
    inserted += 1
    print(f"  [{inserted}] {doc['code']} | {wf['name'][:30]} | {nodo['dept']} | {status} | {created.strftime('%Y-%m-%d')}")

print(f"\nTotal insertados: {inserted}")
print(f"Total en BD ahora: {col.count_documents({})}")
client.close()
