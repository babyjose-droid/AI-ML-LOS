"""Orchestrates one document read: OCR -> classify -> extract -> validate -> quality -> tamper -> mask -> fallback."""
import base64
import io
import os

from . import llm
from .classify import classify
from .extract import REQUIRED, extract, validations
from .mask import mask_image, mask_text
from .ocr import run_ocr
from .quality import assess
from .tamper import signals
from .validators import aadhaar_valid, pan_valid, parse_date

REVIEW_BELOW = float(os.getenv("REVIEW_CONFIDENCE", "0.80"))
FALLBACK_BELOW = float(os.getenv("FALLBACK_CONFIDENCE", "0.80"))


def _allow_specimen() -> bool:
    return os.getenv("ALLOW_SPECIMEN", "true").lower() == "true"


def analyze(data: bytes, content_type: str, declared_type: str | None = None) -> dict:
    ocr = run_ocr(data, content_type)
    text = ocr.text
    doc_type, type_conf, _ = classify(text)
    quality = assess(ocr.pages[0].image) if ocr.pages else {"issues": ["No page could be read"], "ok": False}
    fields = extract(doc_type, ocr)
    engine = "tesseract" if not ocr.pdf_text.strip() else "pdf-text+tesseract"

    # Aadhaar: mask before anything leaves this service
    masked_png, masked = None, False
    if doc_type == "AADHAAR" or declared_type == "AADHAAR":
        number = None
        import re
        for m in re.finditer(r"(?<!\d)([2-9][0-9]{3})\s?([0-9]{4})\s?([0-9]{4})(?!\d)", text):
            if aadhaar_valid("".join(m.groups())):
                number = "".join(m.groups())
                break
        if number:
            masked_png, masked = mask_image(ocr, number)

    required = REQUIRED.get(doc_type, [])

    def overall() -> float:
        if not required:
            return round(type_conf * 0.8, 3)
        cs = [(fields.get(k) or {}).get("confidence", 0.0) for k in required]
        return round(sum(cs) / len(cs) * (0.9 if not quality.get("ok") else 1.0), 3)

    conf = overall()
    # Claude fallback for weak reads (never with an unmasked Aadhaar)
    aadhaar_like = doc_type == "AADHAAR" or declared_type == "AADHAAR"
    if llm.enabled() and (doc_type == "UNKNOWN" or conf < FALLBACK_BELOW) and (not aadhaar_like or masked) and ocr.pages:
        img_png = masked_png if masked_png else _png(ocr.pages[0].image)
        res = llm.read_document(img_png, doc_type if doc_type != "UNKNOWN" else declared_type)
        if res:
            engine += "+claude"
            if doc_type == "UNKNOWN" and res.get("documentType") in REQUIRED:
                doc_type, type_conf = res["documentType"], 0.85
                required = REQUIRED.get(doc_type, [])
            for k, v in res["fields"].items():
                if v in (None, ""):
                    continue
                v = str(v).strip()
                if k == "dob" and "/" in v:
                    v = parse_date(v) or v
                if k == "pan" and not pan_valid(v.upper()):
                    continue
                cur = fields.get(k)
                if not cur or cur["confidence"] < 0.85:
                    fields[k] = {"value": v.upper() if k == "pan" else v, "confidence": 0.88, "source": "claude"}
            conf = overall()

    checks = validations(doc_type, fields)
    tamper = signals(ocr, text, _allow_specimen())
    specimen = "SPECIMEN" in text.upper().replace(" ", "")

    reasons = []
    if not ocr.pages or not text.strip():
        reasons.append("No readable text")
    if doc_type == "UNKNOWN":
        reasons.append("Document type not recognised")
    if declared_type and doc_type not in ("UNKNOWN", declared_type):
        reasons.append(f"Uploaded as {declared_type} but looks like {doc_type}")
    missing = [k for k in required if k not in fields]
    if missing:
        reasons.append("Could not read: " + ", ".join(missing))
    if required and conf < REVIEW_BELOW:
        reasons.append(f"Low reading confidence ({round(conf * 100)}%)")
    reasons += [f"Check failed: {c['check']}" for c in checks if not c["pass"]]
    reasons += quality.get("issues", [])
    if tamper["score"] >= 0.5:
        reasons.append("Possible editing: " + "; ".join(tamper["signals"]))
    if aadhaar_like and not masked and doc_type == "AADHAAR":
        reasons.append("Aadhaar number could not be masked automatically")

    unreadable = (not text.strip()) or (ocr.mean_conf < 0.35 and not ocr.pdf_text.strip()) or \
                 ("Image is blurred" in quality.get("issues", []) and len(fields) <= 1)
    status = "UNREADABLE" if unreadable else ("NEEDS_REVIEW" if reasons else "OK")

    return {
        "status": status,
        "documentType": doc_type,
        "typeConfidence": type_conf,
        "declaredType": declared_type,
        "fields": fields,
        "validations": checks,
        "quality": quality,
        "tamper": tamper,
        "specimen": specimen,
        "overallConfidence": conf,
        "engine": engine,
        "reviewReasons": reasons,
        "masked": masked,
        "maskedImage": base64.b64encode(masked_png).decode() if masked_png else None,
        "text": mask_text(text)[:3000],
        "pages": len(ocr.pages),
    }


def _png(img) -> bytes:
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()
