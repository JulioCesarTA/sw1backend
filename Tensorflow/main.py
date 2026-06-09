"""
FastAPI — NLP + Motor IA Service  (puerto 8001)

Endpoints activos:
  POST /nlp/report-generate        → reporte dinámico (pantalla / word / excel)
  POST /nlp/download               → Word / Excel
  POST /nlp/match-with-docs        → recomienda workflow leyendo texto + documentos
  GET  /nlp/workflow-requirements  → campos requeridos por workflow
  POST /nlp/fill-form              → rellena formulario desde transcripción de voz
  GET  /nlp/optimize-workflow/{id} → WorkflowOptimizer: recomendaciones de nodos
  GET  /nlp/predict-delay/{id}     → DelayPredictor: probabilidad de demora
  GET  /nlp/predict-bottleneck/{id}→ BottleneckPredictor: nodos con mayor riesgo
  GET  /nlp/rank-priority-real     → PriorityRanker: trámites activos por urgencia (TF por workflow)
  GET  /nlp/detect-anomalies       → AnomalyDetector: trámites con comportamiento anómalo (Autoencoder TF)
"""

import logging
import os
from contextlib import asynccontextmanager
from typing import List

from dotenv import load_dotenv
load_dotenv()

from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel

from reports.data_service              import DataService
from reports.report_service            import generate_word, generate_excel
from nlp.prompt_parser                 import PromptParser
from nlp.document_reader               import extract_text
from nlp.form_filler                   import FormFiller
from ai.workflow_matcher               import WorkflowMatcher
from ai.document_classifier            import DocumentClassifier
from ai.workflow_optimizer             import WorkflowOptimizer
from ai.predictor.delay_predictor      import DelayPredictor
from ai.predictor.bottleneck_predictor import BottleneckPredictor
from ai.predictor.priority_ranker      import PriorityRanker
from ai.predictor.anomaly_detector     import AnomalyDetector

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s")
logger = logging.getLogger(__name__)

# -----------------------------------------------------------------------
# Globals
# -----------------------------------------------------------------------
data_svc:        DataService        | None = None
wf_matcher:      WorkflowMatcher    | None = None
doc_clf:         DocumentClassifier | None = None
form_filler:     FormFiller         | None = None
delay_predictor:   DelayPredictor     | None = None
bottleneck_pred:   BottleneckPredictor| None = None
priority_ranker:   PriorityRanker     | None = None
anomaly_detector:  AnomalyDetector    | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global data_svc, wf_matcher, doc_clf, form_filler
    global delay_predictor, bottleneck_pred, priority_ranker, anomaly_detector

    logger.info("▶ Iniciando modelos TensorFlow …")
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

    try:
        anomaly_detector = AnomalyDetector()
        logger.info("✓ AnomalyDetector listo.")
    except Exception as e:
        logger.warning(f"AnomalyDetector no disponible: {e}")
        anomaly_detector = None

    logger.info("✓ Servicio listo en http://localhost:8001")
    yield
    logger.info("Cerrando servicio …")


app = FastAPI(title="NLP + Motor IA Service", version="4.0.0", lifespan=lifespan)

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

class DownloadRequest(BaseModel):
    spec:   dict
    format: str = "excel"


# -----------------------------------------------------------------------
# ① POST /nlp/report-generate
# -----------------------------------------------------------------------
@app.post("/nlp/report-generate")
async def report_generate(req: ReportGenerateRequest):
    if not data_svc:
        raise HTTPException(503, "Servicio no inicializado")

    all_rows = data_svc.get_all_enriched()
    context  = data_svc.extract_context(all_rows)

    parser = PromptParser(
        departments=context["departments"],
        workflows=context["workflows"],
        users=context["users"],
    )
    spec = parser.parse(req.prompt)
    rows = data_svc.filter_rows(all_rows, spec)

    fmt     = spec.get("format", "screen")
    columns = spec.get("columns", ["code", "title", "workflowName", "departmentName",
                                   "status", "userName", "createdAt"])
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

    return {"spec": spec, "data": rows, "total": len(rows)}


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
# ③ POST /nlp/match-with-docs  — texto + archivos reales (usuario-pide)
# -----------------------------------------------------------------------
@app.post("/nlp/match-with-docs")
async def match_with_docs(
    text:  str              = Form(""),
    files: List[UploadFile] = File(default=[]),
):
    if not wf_matcher or not doc_clf:
        raise HTTPException(503, "Servicio no inicializado")

    analyzed_docs: list[dict] = []
    doc_texts:     list[str]  = []
    all_doc_text = text

    for upload in files:
        raw     = await upload.read()
        content = extract_text(upload.filename or "", raw)

        if not content.strip():
            analyzed_docs.append({
                "filename":     upload.filename,
                "detectedType": "IMAGEN",
                "confidence":   0.0,
                "preview":      "(imagen u archivo sin texto — no se puede leer el contenido)",
            })
            doc_texts.append("")
            continue

        top = doc_clf.classify(content, top_k=1)[0]
        analyzed_docs.append({
            "filename":     upload.filename,
            "detectedType": top["type"],
            "confidence":   round(top["prob"] * 100, 1),
            "preview":      content[:300].replace("\n", " "),
        })
        doc_texts.append(content[:1000])
        all_doc_text += " " + content[:500]

    matches = wf_matcher.match_with_doc_texts(
        user_text=text,
        doc_texts=doc_texts,
        all_text=all_doc_text,
    )

    return {"userText": text, "documents": analyzed_docs, "matches": matches}


# -----------------------------------------------------------------------
# ④ GET /nlp/workflow-requirements
# -----------------------------------------------------------------------
@app.get("/nlp/workflow-requirements")
async def get_requirements():
    if not wf_matcher:
        raise HTTPException(503, "WorkflowMatcher no inicializado")
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
# ⑤ POST /nlp/fill-form  — rellena campos desde voz
# -----------------------------------------------------------------------
class FillFormRequest(BaseModel):
    transcript: str
    fields: List[dict] = []

@app.post("/nlp/fill-form")
async def fill_form(req: FillFormRequest):
    if not form_filler:
        raise HTTPException(503, "FormFiller no inicializado")
    return form_filler.fill_form(req.transcript, req.fields)


# -----------------------------------------------------------------------
# ⑥ GET /nlp/optimize-workflow/{id}  — WorkflowOptimizer
# -----------------------------------------------------------------------
@app.get("/nlp/optimize-workflow/{workflow_id}")
async def optimize_workflow(workflow_id: str):
    optimizer = WorkflowOptimizer()
    return optimizer.analyze(workflow_id)


# -----------------------------------------------------------------------
# ⑦ GET /nlp/predict-delay/{id}  — DelayPredictor
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
# ⑧ GET /nlp/predict-bottleneck/{id}  — BottleneckPredictor
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
# ⑨ GET /nlp/rank-priority-real  — PriorityRanker (todos los workflows)
# -----------------------------------------------------------------------
@app.get("/nlp/rank-priority-real")
async def rank_priority_real():
    if not priority_ranker:
        raise HTTPException(503, "PriorityRanker no inicializado")
    ranked = priority_ranker.rank()
    return {"total": len(ranked), "ranked": ranked}


# -----------------------------------------------------------------------
# POST /nlp/rank-priority-real/{workflow_id}  — entrenar + rankear un workflow
# -----------------------------------------------------------------------
@app.post("/nlp/rank-priority-real/{workflow_id}")
async def rank_priority_workflow(workflow_id: str):
    if not priority_ranker:
        raise HTTPException(503, "PriorityRanker no inicializado")
    try:
        return priority_ranker.rank_workflow(workflow_id)
    except ValueError as e:
        raise HTTPException(404, str(e))


# -----------------------------------------------------------------------
# ⑩ GET /nlp/detect-anomalies  — AnomalyDetector (todos los workflows)
# -----------------------------------------------------------------------
@app.get("/nlp/detect-anomalies")
async def detect_anomalies():
    if not anomaly_detector:
        raise HTTPException(503, "AnomalyDetector no inicializado")
    return anomaly_detector.detect()


# -----------------------------------------------------------------------
# ⑪ POST /nlp/detect-anomalies/{workflow_id}  — entrenar + detectar para un workflow
# -----------------------------------------------------------------------
@app.post("/nlp/detect-anomalies/{workflow_id}")
async def detect_anomalies_workflow(workflow_id: str):
    if not anomaly_detector:
        raise HTTPException(503, "AnomalyDetector no inicializado")
    try:
        return anomaly_detector.train_and_detect_workflow(workflow_id)
    except ValueError as e:
        raise HTTPException(404, str(e))


# -----------------------------------------------------------------------
# ⑫ POST /nlp/reload-workflows  — recarga workflows sin reiniciar el servicio
# -----------------------------------------------------------------------
@app.post("/nlp/reload-workflows")
async def reload_workflows():
    if not wf_matcher:
        raise HTTPException(503, "WorkflowMatcher no inicializado")
    wf_matcher.reload()
    return {"ok": True, "workflows": len(wf_matcher.workflows)}


# -----------------------------------------------------------------------
# Health
# -----------------------------------------------------------------------
@app.get("/health")
async def health():
    return {
        "status":            "ok",
        "doc_clf_loaded":    doc_clf is not None,
        "workflows_loaded":  len(wf_matcher.workflows) if wf_matcher else 0,
        "delay_predictor":   delay_predictor is not None,
        "bottleneck_pred":   bottleneck_pred is not None,
        "priority_ranker":   priority_ranker is not None,
        "anomaly_detector":  anomaly_detector is not None,
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8001))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=False)
