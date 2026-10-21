"""Rhythm AI document service. Internal API used by the LOS backend; it does not store documents."""
import logging

from fastapi import Body, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response

from . import docgen, llm
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


@app.post("/v1/field/photos/analyze")
async def analyze_field_photos(photos: list[UploadFile] = File(...), id_images: list[UploadFile] = File(default=[])):
    """
    Field-visit photo checks. Each photo's file name starts with its kind (front-, inside-, stock-, owner-, animals-,
    vehicle-, odometer-, residence-). id_images are KYC documents for the owner face check. Nothing is stored.
    """
    from . import fieldphotos
    if len(photos) > 20:
        raise HTTPException(413, "Too many photos (max 20)")
    pin = []
    for f in photos:
        kind = (f.filename or "other").split("-")[0].split(".")[0].lower()
        pin.append((f.filename or "photo", kind, await f.read()))
    ids = [((f.filename or "ID").split(".")[0].upper(), await f.read()) for f in id_images]
    return fieldphotos.analyse(pin, ids)


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


@app.post("/v1/assistant/answer")
async def assistant_answer(body: dict):
    """Policy assistant: {question, passages: [{id, title, text}]} -> {answer} (answer is null without a Claude key)."""
    q = str(body.get("question") or "")[:1000]
    passages = body.get("passages") or []
    if not q or not isinstance(passages, list):
        raise HTTPException(422, "question and passages are required")
    answer = llm.answer_question(q, passages)
    return {"answer": answer, "engine": "claude" if answer else "none"}


@app.post("/v1/collateral/documents/analyze")
async def collateral_document(file: UploadFile = File(...), doc_type: str = Form(...)):
    """Collateral Document AI: fields with confidence per document type; EC transactions."""
    from . import collateral
    data = await file.read()
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "File too large")
    return collateral.analyze_document(data, file.content_type or "application/pdf", doc_type.upper())


@app.post("/v1/assets/documents/analyze")
async def asset_document(file: UploadFile = File(...), doc_type: str = Form(...)):
    """Asset finance Document AI: quotation, invoice, down-payment receipt, insurance, RC."""
    from . import asset
    data = await file.read()
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "File too large")
    return asset.analyze_document(data, file.content_type or "application/pdf", doc_type.upper())


@app.post("/v1/trade/invoices/analyze")
async def trade_invoice(file: UploadFile = File(...)):
    """Trade finance Document AI: invoice offered for receivables, vendor or dealer finance."""
    from . import trade
    data = await file.read()
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "File too large")
    return trade.analyze_invoice(data, file.content_type or "application/pdf")


@app.post("/v1/collateral/media/analyze")
async def collateral_media(file: UploadFile = File(...)):
    """Collateral Vision AI: EXIF time and GPS, quality and editing hints for one photo."""
    from . import collateral
    return collateral.analyze_media(await file.read())


DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


@app.post("/v1/docs/pdf")
def doc_pdf(body: dict = Body(...)):
    """A loan document's HTML (already filled in by the LOS) as a PDF."""
    html = body.get("html") or ""
    if not html.strip() or len(html) > 2_000_000:
        raise HTTPException(422, "html is empty or too large")
    try:
        pdf = docgen.render_pdf(html, body.get("title"))
    except Exception as e:  # noqa: BLE001
        logging.exception("pdf failed")
        raise HTTPException(422, f"Could not make the PDF: {type(e).__name__}")
    return Response(content=pdf, media_type="application/pdf")


@app.post("/v1/docs/docx-to-html")
async def doc_from_word(file: UploadFile = File(...)):
    """A Word template (.docx with {{fields}} typed in) as HTML for the template editor."""
    data = await file.read()
    if not data or len(data) > MAX_BYTES:
        raise HTTPException(413, "File is empty or larger than 10 MB")
    if not data.startswith(b"PK"):
        raise HTTPException(415, "Upload a Word .docx file")
    try:
        return docgen.docx_to_html(data)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(422, f"Could not read the Word file: {type(e).__name__}")
