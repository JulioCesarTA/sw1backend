from fastapi import FastAPI

from bottleneck_ai import analyze_bottlenecks
from diagram_ai import load_dotenv_file, process_diagram_command, process_diagram_voice_command
from form_voice_ai import process_form_voice_design, process_form_voice_fill
from worky_ai import analyze_worky_assistant


load_dotenv_file()
app = FastAPI(title="Workflow IA Service")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/diagram-command")
def diagram_command(body: dict):
    return process_diagram_command(body)


@app.post("/diagram-voice-command")
def diagram_voice_command(body: dict):
    return process_diagram_voice_command(body)


@app.post("/bottleneck-analysis")
def bottleneck_analysis(body: dict):
    return analyze_bottlenecks(body)


@app.post("/worky-suggestions")
def worky_suggestions(body: dict):
    return analyze_worky_assistant(body)


@app.post("/form-voice-fill")
def form_voice_fill(body: dict):
    return process_form_voice_fill(body)


@app.post("/form-voice-design")
def form_voice_design(body: dict):
    return process_form_voice_design(body)
