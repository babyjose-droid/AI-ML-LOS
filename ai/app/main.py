"""Rhythm AI document service. Internal API used by the LOS backend; it does not store documents."""
import logging

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from . import llm
from .analyze import analyze

logging.basicConfig(level=logging.INFO)
app = FastAPI(title="Rhythm AI document service", version="0.2.0")

ALLOWED = {"application/pdf", "image/jpeg", "image/png"}
MAX_BYTES = 10 * 1024 * 1024


@app.get("/health")
def health():
    return {"status": "UP", "claudeFallback": llm.enabled(), "model": llm.MODEL if llm.enabled() else None}


@app.post("/v1/documents/analyze")
async def analyze_document(file: UploadFile = File(...), declared_type: str | None = Form(None)):
    ct = file.content_type or ""
    if ct not in ALLOWED:
        raise HTTPException(415, "Only PDF, JPG and PNG are supported")
    data = await file.read()
    if not data or len(data) > MAX_BYTES:
        raise HTTPException(413, "File is empty or larger than 10 MB")
    try:
        return analyze(data, ct, declared_type)
    except Exception as e:  # noqa: BLE001
        logging.exception("analysis failed")
        return {"status": "UNREADABLE", "documentType": "UNKNOWN", "typeConfidence": 0, "declaredType": declared_type,
                "fields": {}, "validations": [], "quality": {"issues": ["File could not be processed"], "ok": False},
                "tamper": {"score": 0, "signals": []}, "overallConfidence": 0, "engine": "none",
                "reviewReasons": [f"File could not be processed: {type(e).__name__}"], "masked": False, "maskedImage": None,
                "text": "", "pages": 0, "specimen": False}
