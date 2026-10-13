"""Rhythm AI document service. Internal API used by the LOS backend; it does not store documents."""
import logging

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from . import llm
from .analyze import analyze
from .statements_analyze import PasswordRequired, analyse_statement, unlock

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


@app.post("/v1/statements/analyze")
async def analyze_statement(file: UploadFile = File(...), password: str | None = Form(None),
                            holder_hint: str | None = Form(None), declared_income: float | None = Form(None)):
    ct = file.content_type or ""
    if ct not in ALLOWED:
        raise HTTPException(415, "Only PDF, JPG and PNG are supported")
    data = await file.read()
    if not data or len(data) > MAX_BYTES:
        raise HTTPException(413, "File is empty or larger than 10 MB")
    try:
        return analyse_statement(data, ct, password, holder_hint, declared_income)
    except PasswordRequired:
        raise HTTPException(422, "PASSWORD_REQUIRED")
    except Exception as e:  # noqa: BLE001
        logging.exception("statement analysis failed")
        return {"status": "UNREADABLE", "documentType": "BANK_STATEMENT", "engine": "none", "transactions": [], "transactionCount": 0,
                "monthly": [], "summary": {}, "reconciliation": {"checked": 0, "passed": 0, "rate": 0, "breaks": []},
                "tamper": {"score": 0, "signals": []}, "notes": [], "reviewReasons": [f"File could not be processed: {type(e).__name__}"],
                "overallConfidence": 0, "headerFound": False, "passwordProtected": False}


@app.post("/v1/pdf/unlock")
async def unlock_pdf(file: UploadFile = File(...), password: str = Form(...)):
    """Removes the password from a PDF so the LOS can store an unlocked copy and forget the password."""
    data = await file.read()
    try:
        out, was = unlock(data, password)
    except PasswordRequired:
        raise HTTPException(422, "WRONG_PASSWORD")
    except Exception:  # noqa: BLE001
        raise HTTPException(422, "NOT_A_PDF")
    import base64
    return {"wasEncrypted": was, "pdf": base64.b64encode(out).decode()}


@app.post("/v1/vkyc/analyze")
async def analyze_vkyc(frames: list[UploadFile] = File(...), id_images: list[UploadFile] = File(default=[]),
                       expected_pan: str | None = Form(None)):
    """
    Video KYC assistance. Each frame's file name starts with its step: front-, left-, right-, code- or pan-.
    id_images are the ID documents on file (file name = document type). Nothing here is stored.
    """
    from . import vkyc
    if not vkyc.available():
        raise HTTPException(503, "Face models are not installed in this image")
    if len(frames) > 60:
        raise HTTPException(413, "Too many frames (max 60)")
    fin = []
    for f in frames:
        step = (f.filename or "front").split("-")[0].split(".")[0].lower()
        fin.append((step if step in vkyc.STEPS else "front", await f.read()))
    ids = [((f.filename or "ID").split(".")[0].upper(), await f.read()) for f in id_images]

    def read_pan(data: bytes):
        try:
            r = analyze(data, "image/jpeg", "PAN")
            v = (r.get("fields") or {}).get("pan") or {}
            return v.get("value")
        except Exception:  # noqa: BLE001
            return None

    return vkyc.analyse(fin, ids, read_pan, expected_pan)


@app.post("/v1/models/train")
async def train_model(body: dict):
    """Fits a PD model on the loan book the LOS sends: {features: [...], rows: [{features: {...}, bad, championPd}], l2?}."""
    from . import train
    feats = body.get("features") or []
    rows = body.get("rows") or []
    if not feats or not rows:
        raise HTTPException(422, "features and rows are required")
    if len(rows) > 200_000:
        raise HTTPException(413, "Too many rows (max 200,000)")
    try:
        return train.train(rows, feats, float(body.get("l2", 1.0)))
    except ValueError as e:
        raise HTTPException(422, str(e))
