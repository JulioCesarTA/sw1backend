# IA Service

Servicio externo de IA para workflows, implementado con FastAPI.

Endpoints:
- `POST /diagram-command`
- `POST /bottleneck-analysis`
- `POST /worky-suggestions`
- `GET /health`

Variables requeridas:
- `CLAUDE_API_KEY`
- `MONGODB_URI`

Ejecutar local:

```bash
cd IA
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0 --port 8001
```

Integracion:
- El backend sigue exponiendo `/api/workflow-ai/*`.
- Esas rutas ahora hacen proxy hacia `AI_BASE_URL`.
