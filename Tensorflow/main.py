"""
FastAPI — NLP Service  (puerto 8001)

Endpoints:
  POST /nlp/analyze              → reporte dinámico (preview)
  POST /nlp/download             → Word / Excel
  POST /nlp/match-workflow       → recomienda workflows por texto
  POST /nlp/match-with-docs      → recomienda workflow leyendo documentos subidos
  GET  /nlp/workflow-requirements → config de requisitos por workflow
  POST /nlp/workflow-requirements → guardar requisitos (admin)
"""

import logging
import os
from contextlib import asynccontextmanager
from typing import List, Optional

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

from services.nlp_service        import NLPService
from services.entity_extractor   import extract_entities
from services.data_service       import DataService
from services.report_service     import generate_word, generate_excel
from services.prompt_parser      import PromptParser
from services.workflow_matcher   import WorkflowMatcher
from services.document_reader    import extract_text
from services.document_classifier import DocumentClassifier
from services.form_filler        import FormFiller
from services.routing_engine     import RoutingEngine, MAX_SEQ
from services.workflow_optimizer import WorkflowOptimizer
from services.predictor_service  import DelayPredictor, BottleneckPredictor, PriorityRanker

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s")
logger = logging.getLogger(__name__)

# -----------------------------------------------------------------------
# Globals
# -----------------------------------------------------------------------
nlp_svc:    NLPService         | None = None
data_svc:   DataService        | None = None
wf_matcher: WorkflowMatcher    | None = None
doc_clf:    DocumentClassifier | None = None
form_filler:       FormFiller          | None = None
routing_engine:    RoutingEngine       | None = None
delay_predictor:   DelayPredictor      | None = None
bottleneck_pred:   BottleneckPredictor | None = None
priority_ranker:   PriorityRanker      | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global nlp_svc, data_svc, wf_matcher, doc_clf, form_filler
    global routing_engine, delay_predictor, bottleneck_pred, priority_ranker

    logger.info("▶ Iniciando modelos TensorFlow …")
    nlp_svc     = NLPService()
    doc_clf     = DocumentClassifier()
    form_filler = FormFiller()

    try:
        data_svc   = DataService()
        wf_matcher = WorkflowMatcher(data_svc.db)
        logger.info("✓ Spring Boot API conectada, workflows cargados.")
    except Exception as e:
        logger.warning(f"Spring Boot no disponible: {e}. Arrancando sin workflows.")
        data_svc   = None
        wf_matcher = None

    try:
        routing_engine = RoutingEngine()
        logger.info("✓ RoutingEngine listo (4 modelos entrenados).")
    except Exception as e:
        logger.warning(f"RoutingEngine no disponible: {e}. Arrancando sin routing.")
        routing_engine = None

    try:
        delay_predictor = DelayPredictor()
        logger.info("✓ DelayPredictor listo.")
    except Exception as e:
        logger.warning(f"DelayPredictor no disponible: {e}")
        delay_predictor = None

    try:
        bottleneck_pred = BottleneckPredictor()
        logger.info("✓ BottleneckPredictor listo.")
    except Exception as e:
        logger.warning(f"BottleneckPredictor no disponible: {e}")
        bottleneck_pred = None

    try:
        priority_ranker = PriorityRanker()
        logger.info("✓ PriorityRanker listo.")
    except Exception as e:
        logger.warning(f"PriorityRanker no disponible: {e}")
        priority_ranker = None

    logger.info("✓ Servicio NLP listo en http://localhost:8001")
    yield
    logger.info("Cerrando servicio NLP …")


app = FastAPI(title="NLP Service", version="3.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# -----------------------------------------------------------------------
# Schemas
# -----------------------------------------------------------------------
class ReportGenerateRequest(BaseModel):
    prompt: str

class AnalyzeRequest(BaseModel):
    text:   str
    format: str = "screen"

class DownloadRequest(BaseModel):
    spec:   dict
    format: str = "excel"

class MatchWorkflowRequest(BaseModel):
    text:     str
    userDocs: List[str] = []


# -----------------------------------------------------------------------
# ① POST /nlp/report-generate  (reemplaza analyze para reportes)
# -----------------------------------------------------------------------
@app.post("/nlp/report-generate")
async def report_generate(req: ReportGenerateRequest):
    if not data_svc:
        raise HTTPException(503, "Servicio no inicializado")

    # 1. Obtener todos los trámites enriquecidos
    all_rows = data_svc.get_all_enriched()

    # 2. Extraer contexto dinámico (departamentos, workflows, usuarios reales)
    context = data_svc.extract_context(all_rows)

    # 3. Parsear el prompt contra el contexto real
    parser = PromptParser(
        departments=context["departments"],
        workflows=context["workflows"],
        users=context["users"],
    )
    spec = parser.parse(req.prompt)

    # 4. Filtrar y ordenar en Python
    rows = data_svc.filter_rows(all_rows, spec)

    # 5. Si el formato es word o excel: generar archivo directamente
    fmt = spec.get("format", "screen")
    columns = spec.get("columns", ["code", "title", "workflowName", "departmentName", "status", "userName", "createdAt"])
    title   = spec.get("title", "Reporte de Trámites")

    if fmt in ("word", "excel"):
        group_by = spec.get("groupBy")
        if fmt == "word":
            content  = generate_word(title, columns, rows, group_by=group_by)
            media    = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            filename = "reporte.docx"
        else:
            content  = generate_excel(title, columns, rows, group_by=group_by)
            media    = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            filename = "reporte.xlsx"
        return Response(content=content, media_type=media,
                        headers={"Content-Disposition": f'attachment; filename="{filename}"'})

    # 6. Modo pantalla: devolver datos JSON
    return {
        "spec":  spec,
        "data":  rows,
        "total": len(rows),
    }


# -----------------------------------------------------------------------
# ② POST /nlp/analyze  (legacy — mantener para compatibilidad)
# -----------------------------------------------------------------------
@app.post("/nlp/analyze")
async def analyze(req: AnalyzeRequest):
    if not nlp_svc or not data_svc:
        raise HTTPException(503, "Servicio no inicializado")
    nlp_result = nlp_svc.analyze(req.text)
    spec       = extract_entities(nlp_result, requested_format=req.format)
    try:
        rows = data_svc.query(spec)
    except Exception as e:
        logger.error(f"Error MongoDB: {e}")
        rows = []
    return {
        "intent":     nlp_result["intent"],
        "confidence": nlp_result["confidence"],
        "ner_tokens": nlp_result["ner_tokens"],
        "spec":       spec,
        "data":       rows,
        "total":      len(rows),
    }


# -----------------------------------------------------------------------
# ② POST /nlp/download
# -----------------------------------------------------------------------
@app.post("/nlp/download")
async def download(req: DownloadRequest):
    if not data_svc:
        raise HTTPException(503, "Servicio no inicializado")
    fmt     = req.format.lower()
    rows    = data_svc.query(req.spec)
    title   = req.spec.get("title", "Reporte")
    columns = req.spec.get("columns", ["tramiteId", "workflowName",
                                        "departmentName", "status",
                                        "userName", "createdAt"])
    if fmt == "word":
        content  = generate_word(title, columns, rows)
        media    = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        filename = "reporte.docx"
    elif fmt == "excel":
        content  = generate_excel(title, columns, rows)
        media    = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        filename = "reporte.xlsx"
    else:
        raise HTTPException(400, "Formato inválido.")
    return Response(content=content, media_type=media,
                    headers={"Content-Disposition": f'attachment; filename="{filename}"'})


# -----------------------------------------------------------------------
# ③ POST /nlp/match-workflow  (solo texto)
# -----------------------------------------------------------------------
@app.post("/nlp/match-workflow")
async def match_workflow(req: MatchWorkflowRequest):
    if not wf_matcher:
        raise HTTPException(503, "WorkflowMatcher no inicializado")
    matches = wf_matcher.match(req.text, req.userDocs)
    return {"query": req.text, "matches": matches}


# -----------------------------------------------------------------------
# ④ POST /nlp/match-with-docs  — PRINCIPAL: texto + archivos reales
# -----------------------------------------------------------------------
@app.post("/nlp/match-with-docs")
async def match_with_docs(
    text:  str            = Form(""),
    files: List[UploadFile] = File(default=[]),
):
    if not wf_matcher or not doc_clf:
        raise HTTPException(503, "Servicio no inicializado")

    # — Leer y clasificar cada documento subido —
    analyzed_docs: list[dict] = []
    doc_texts:     list[str]  = []  # texto real por archivo (vacío para imágenes)
    all_doc_text = text

    for upload in files:
        raw     = await upload.read()
        content = extract_text(upload.filename or "", raw)

        if not content.strip():
            # Imagen u archivo sin texto extraíble — TF no puede leerlo
            analyzed_docs.append({
                "filename":     upload.filename,
                "detectedType": "IMAGEN",
                "confidence":   0.0,
                "preview":      "(imagen u archivo sin texto — no se puede leer el contenido)",
            })
            doc_texts.append("")  # texto vacío → no cubre ningún campo
            continue

        # TF clasifica el tipo de documento (para mostrar en el panel)
        top = doc_clf.classify(content, top_k=1)[0]

        analyzed_docs.append({
            "filename":     upload.filename,
            "detectedType": top["type"],
            "confidence":   round(top["prob"] * 100, 1),
            "preview":      content[:300].replace("\n", " "),
        })

        doc_texts.append(content[:1000])        # texto real para matching de campos
        all_doc_text += " " + content[:500]     # contexto acumulado para recomendar workflow

    # — Matchear con workflows usando el texto real de cada doc —
    matches = wf_matcher.match_with_doc_texts(
        user_text = text,
        doc_texts = doc_texts,
        all_text  = all_doc_text,
    )

    return {
        "userText":  text,
        "documents": analyzed_docs,
        "matches":   matches,
    }


# -----------------------------------------------------------------------
# ⑤ GET /nlp/workflow-requirements  (ahora lee campos reales de Spring Boot)
# -----------------------------------------------------------------------
@app.get("/nlp/workflow-requirements")
async def get_requirements():
    if not wf_matcher:
        raise HTTPException(503, "WorkflowMatcher no inicializado")
    # Devuelve los campos FILE reales del primer nodo de cada workflow
    result = []
    for w in wf_matcher.workflows:
        req = wf_matcher.field_requirements.get(w["id"], {})
        result.append({
            "workflowId":   w["id"],
            "workflowName": w["name"],
            "requiredDocs": req.get("required", []),
            "optionalDocs": req.get("optional", []),
        })
    return result


# -----------------------------------------------------------------------
# ⑦ POST /nlp/fill-form  — rellena campos de formulario desde voz (TF)
# -----------------------------------------------------------------------
class FillFormRequest(BaseModel):
    transcript: str
    fields: List[dict] = []   # [{name, type, required}, ...]

@app.post("/nlp/fill-form")
async def fill_form(req: FillFormRequest):
    if not form_filler:
        raise HTTPException(503, "FormFiller no inicializado")
    result = form_filler.fill_form(req.transcript, req.fields)
    return result


# -----------------------------------------------------------------------
# ⑧ POST /nlp/route-optimize  — RouteOptimizer (Model 1)
# -----------------------------------------------------------------------
class RouteOptimizeRequest(BaseModel):
    workflowId:   str
    workflowName: str = ""
    numNodes:     int = 4
    deptLoad:     dict = {}   # {deptName: count}
    estimatedHours: float = 8.0

@app.post("/nlp/route-optimize")
async def route_optimize(req: RouteOptimizeRequest):
    if not routing_engine:
        raise HTTPException(503, "RoutingEngine no inicializado")

    # Derive dept_load_ratio from deptLoad map (0-1)
    depts = routing_engine.DEPARTMENTS if routing_engine else []
    total_load = sum(req.deptLoad.values()) if req.deptLoad else 0
    dept_load_ratio = min(total_load / max(len(depts) * 5, 1), 1.0)

    # workflow_complexity: proxy from numNodes (2-10 → 1-5)
    complexity = max(1.0, min(5.0, 1 + (req.numNodes - 2) * 4 / 8))

    import datetime
    now = datetime.datetime.now()
    time_of_day = (now.hour * 60 + now.minute) / (24 * 60)

    result = routing_engine.predict_route(
        workflow_complexity     = complexity,
        num_nodes               = req.numNodes,
        dept_load_ratio         = dept_load_ratio,
        time_of_day             = time_of_day,
        estimated_duration_hours= req.estimatedHours,
    )

    route_score = result["route_quality_score"]
    reasoning = (
        f"Flujo {'simple' if complexity <= 2 else 'complejo'} con {req.numNodes} nodos. "
        f"Carga departamental {round(dept_load_ratio * 100)}%. "
        f"Calidad de ruta estimada: {round(route_score * 100)}%."
    )

    return {
        "workflowId":    req.workflowId,
        "recommendedDept": result["recommended_dept"],
        "routeScore":    result["route_quality_score"],
        "confidence":    result["confidence"],
        "alternatives":  result["alternatives"],
        "reasoning":     reasoning,
    }


# -----------------------------------------------------------------------
# ⑨ POST /nlp/predict-risk  — RiskPredictor (Model 2)
# -----------------------------------------------------------------------
class NodeHistoryItem(BaseModel):
    node:            str
    expectedMinutes: float
    actualMinutes:   float
    deptName:        str = ""

class PredictRiskRequest(BaseModel):
    tramiteId:   str
    nodeHistory: List[NodeHistoryItem] = []

@app.post("/nlp/predict-risk")
async def predict_risk(req: PredictRiskRequest):
    if not routing_engine:
        raise HTTPException(503, "RoutingEngine no inicializado")

    history_encoded = []
    depts = routing_engine.DEPARTMENTS if routing_engine else []
    for h in req.nodeHistory:
        time_ratio = h.actualMinutes / max(h.expectedMinutes, 1)
        dept_idx   = depts.index(h.deptName) if h.deptName in depts else 0
        history_encoded.append({
            "time_ratio":        time_ratio,
            "node_type_encoded": min(time_ratio / 3.0, 1.0),
            "dept_encoded":      dept_idx / max(len(depts) - 1, 1),
        })

    result = routing_engine.predict_risk(history_encoded)
    risk   = result["risk_score"]
    b_idx  = result["bottleneck_idx"]

    if risk >= 0.7:
        risk_level = "HIGH"
        recommendation = "Reasignar recursos inmediatamente y escalar al supervisor."
    elif risk >= 0.4:
        risk_level = "MEDIUM"
        recommendation = "Monitorear de cerca y notificar al equipo responsable."
    else:
        risk_level = "LOW"
        recommendation = "Continuar con el flujo normal."

    # Estimate delay hours
    delay_hours = 0.0
    if req.nodeHistory and b_idx < len(req.nodeHistory):
        h = req.nodeHistory[b_idx]
        delay_hours = max(0, (h.actualMinutes - h.expectedMinutes) / 60)

    bottleneck_name = (
        req.nodeHistory[b_idx].node
        if req.nodeHistory and b_idx < len(req.nodeHistory)
        else "unknown"
    )

    return {
        "tramiteId":          req.tramiteId,
        "riskScore":          result["risk_score"],
        "riskLevel":          risk_level,
        "predictedBottleneck": bottleneck_name,
        "estimatedDelayHours": round(delay_hours, 2),
        "recommendation":     recommendation,
    }


# -----------------------------------------------------------------------
# ⑩ POST /nlp/detect-anomaly  — AnomalyDetector (Model 3)
# -----------------------------------------------------------------------
class DetectAnomalyRequest(BaseModel):
    tramiteId:          str
    totalDurationHours: float
    numNodes:           int
    avgTimePerNode:     float
    deptSwitches:       int
    completionRate:     float
    overtimeRatio:      float
    weekendActivity:    float
    reassignmentCount:  int

@app.post("/nlp/detect-anomaly")
async def detect_anomaly(req: DetectAnomalyRequest):
    if not routing_engine:
        raise HTTPException(503, "RoutingEngine no inicializado")

    import numpy as np
    features = np.array([
        req.totalDurationHours,
        float(req.numNodes),
        req.avgTimePerNode,
        float(req.deptSwitches),
        req.completionRate,
        req.overtimeRatio,
        req.weekendActivity,
        float(req.reassignmentCount),
    ], dtype=np.float32)

    result = routing_engine.detect_anomaly(features)

    # Build details and normal range info
    details = (
        f"Error de reconstrucción: {result['reconstruction_error']:.4f} "
        f"(umbral: {result['threshold']:.4f}). "
        f"{'Patrón anómalo detectado' if result['is_anomaly'] else 'Patrón dentro de lo normal'}."
    )
    normal_range = {
        "totalDurationHours": "4-12h",
        "numNodes": "3-8",
        "deptSwitches": "0-3",
        "completionRate": "0.7-1.0",
        "overtimeRatio": "0.0-0.2",
    }

    return {
        "tramiteId":   req.tramiteId,
        "anomalyScore": result["anomaly_score"],
        "isAnomaly":   result["is_anomaly"],
        "anomalyType": result["anomaly_type"],
        "details":     details,
        "normalRange": normal_range,
    }


# -----------------------------------------------------------------------
# ⑪ POST /nlp/prioritize  — PriorityRanker (Model 4)
# -----------------------------------------------------------------------
class TramiteForPriority(BaseModel):
    id:                  str
    waitHours:           float
    riskScore:           float
    workflowComplexity:  float = 1.0
    slaRemainingHours:   float = 24.0
    totalSlaHours:       float = 72.0
    numPendingDeps:      int   = 0
    deptOverloadScore:   float = 0.0

class PrioritizeRequest(BaseModel):
    tramites: List[TramiteForPriority] = []

@app.post("/nlp/prioritize")
async def prioritize(req: PrioritizeRequest):
    if not routing_engine:
        raise HTTPException(503, "RoutingEngine no inicializado")

    raw = [t.dict() for t in req.tramites]
    # Rename camelCase to snake_case for the engine
    mapped = []
    for t in raw:
        mapped.append({
            "id":                   t["id"],
            "wait_hours":           t["waitHours"],
            "risk_score":           t["riskScore"],
            "workflow_complexity":  t["workflowComplexity"],
            "sla_remaining_hours":  t["slaRemainingHours"],
            "total_sla_hours":      t["totalSlaHours"],
            "num_pending_deps":     t["numPendingDeps"],
            "dept_overload_score":  t["deptOverloadScore"],
        })

    ranked = routing_engine.rank_priority(mapped)
    ranked.sort(key=lambda x: x["priority_score"], reverse=True)

    result = []
    for rank_pos, t in enumerate(ranked, 1):
        score = t["priority_score"]
        if score >= 70:
            urgency = "CRITICAL"
        elif score >= 50:
            urgency = "HIGH"
        elif score >= 30:
            urgency = "MEDIUM"
        else:
            urgency = "LOW"

        reason_parts = []
        if t["wait_hours"] > 24:
            reason_parts.append(f"espera larga ({t['wait_hours']:.0f}h)")
        if t["risk_score"] > 0.6:
            reason_parts.append("alto riesgo de retraso")
        sla_ratio = t["sla_remaining_hours"] / max(t["total_sla_hours"], 1)
        if sla_ratio < 0.2:
            reason_parts.append("SLA casi vencido")
        reason = "; ".join(reason_parts) if reason_parts else "prioridad estándar"

        result.append({
            "id":           t["id"],
            "priorityScore": round(score, 2),
            "rank":         rank_pos,
            "urgencyLevel": urgency,
            "reason":       reason,
        })

    return {"prioritized": result}


# -----------------------------------------------------------------------
# ⑫ GET /nlp/optimize-workflow/{workflowId}  — WorkflowOptimizer
# -----------------------------------------------------------------------
@app.get("/nlp/optimize-workflow/{workflow_id}")
async def optimize_workflow(workflow_id: str):
    optimizer = WorkflowOptimizer()
    result    = optimizer.analyze(workflow_id)
    return result


# -----------------------------------------------------------------------
# ⑬ GET /nlp/predict-delay/{workflowId}  — DelayPredictor
# -----------------------------------------------------------------------
@app.get("/nlp/predict-delay/{workflow_id}")
async def predict_delay(workflow_id: str):
    if not delay_predictor:
        raise HTTPException(503, "DelayPredictor no inicializado")
    try:
        return delay_predictor.predict(workflow_id)
    except ValueError as e:
        raise HTTPException(404, str(e))


# -----------------------------------------------------------------------
# ⑭ GET /nlp/predict-bottleneck/{workflowId}  — BottleneckPredictor
# -----------------------------------------------------------------------
@app.get("/nlp/predict-bottleneck/{workflow_id}")
async def predict_bottleneck(workflow_id: str):
    if not bottleneck_pred:
        raise HTTPException(503, "BottleneckPredictor no inicializado")
    try:
        return bottleneck_pred.predict(workflow_id)
    except ValueError as e:
        raise HTTPException(404, str(e))


# -----------------------------------------------------------------------
# ⑮ GET /nlp/rank-priority-real  — PriorityRanker (real DB data)
# -----------------------------------------------------------------------
@app.get("/nlp/rank-priority-real")
async def rank_priority_real():
    if not priority_ranker:
        raise HTTPException(503, "PriorityRanker no inicializado")
    ranked = priority_ranker.rank()
    return {"total": len(ranked), "ranked": ranked}


# -----------------------------------------------------------------------
# Health
# -----------------------------------------------------------------------
@app.get("/health")
async def health():
    return {
        "status":           "ok",
        "tf_loaded":        nlp_svc is not None,
        "doc_clf_loaded":   doc_clf is not None,
        "workflows_loaded": len(wf_matcher.workflows) if wf_matcher else 0,
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8001))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)
