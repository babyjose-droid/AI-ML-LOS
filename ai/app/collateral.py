"""
Collateral Document AI and Collateral Vision AI.

Documents: OCR (text layer first, Tesseract for scans), then label-based field extraction per document type and
EC transaction parsing. Every field carries a confidence; anything under 0.85 is sent to a person to confirm (COL-E01).
Photos: EXIF time and GPS, image quality (blur, darkness, glare) and editing-software hints.

AI extracts and flags evidence. It does not certify title or value: a legal reviewer and an empanelled valuer do.
"""
import io
import re
from datetime import date, datetime

from PIL import Image

from . import fieldphotos, quality
from .ocr import run_ocr

CONFIRM_BELOW = 0.85

# label synonyms per field (lower case, matched at the start of a "label: value" line)
LABELS = {
    "owner": ["owner", "owner name", "name of owner", "purchaser", "vendee", "registered owner", "khata holder", "assessee", "name of the owner"],
    "seller": ["seller", "vendor", "executant"],
    "surveyNo": ["survey no", "survey number", "s. no", "cts no", "cts number", "gat no", "plot no"],
    "municipalId": ["property id", "municipal id", "khata no", "khata number", "assessment no", "property card no", "upic"],
    "areaSqft": ["area", "built-up area", "builtup area", "plot area", "carpet area", "area (sq ft)"],
    "registrationNo": ["registration no", "document no", "doc no", "registration number", "regn no"],
    "registrationDate": ["date of registration", "registration date", "date"],
    "office": ["sub-registrar", "sub registrar office", "office"],
    "periodFrom": ["period from", "from"],
    "periodTo": ["period to", "to"],
    "taxPaidUpTo": ["paid up to", "tax paid up to", "period paid", "paid for year"],
    "permittedUse": ["permitted use", "use", "land use"],
    "marketValue": ["market value", "fair market value"],
    "realisableValue": ["realisable value", "realizable value"],
    "distressValue": ["distress value", "forced sale value"],
    "valuer": ["valuer", "valued by", "name of valuer"],
    "valuationDate": ["date of valuation", "valuation date", "date of inspection"],
    "method": ["method", "method of valuation"],
    "insurer": ["insurer", "insurance company"],
    "policyNo": ["policy no", "policy number"],
    "sumInsured": ["sum insured", "cover", "insured value"],
    "validTill": ["valid till", "expiry", "policy expiry", "period of insurance to"],
    "assignedTo": ["assigned to", "loss payee", "hypothecated to", "hypothecation", "financier"],
    "vehicleRegNo": ["vehicle registration no", "vehicle no", "registration mark"],
    "chassisNo": ["chassis no", "chassis number"],
    "makeModel": ["maker's name", "make/model", "make & model", "model"],
    "manufactureYear": ["year of manufacture", "manufacturing year", "mfg year"],
}

NEEDED = {
    "SALE_DEED": ["owner", "surveyNo", "areaSqft", "registrationNo", "registrationDate"],
    "MOTHER_DEED": ["owner", "seller", "surveyNo", "registrationDate"],
    "EC": ["periodFrom", "periodTo", "surveyNo"],
    "KHATA": ["owner", "municipalId"],
    "PROPERTY_TAX": ["owner", "municipalId", "taxPaidUpTo"],
    "APPROVED_PLAN": ["permittedUse", "areaSqft"],
    "OC": ["permittedUse", "areaSqft"],
    "LAND_RECORD": ["owner", "surveyNo", "permittedUse"],
    "CONVERSION_ORDER": ["surveyNo", "permittedUse"],
    "VALUATION_REPORT": ["marketValue", "realisableValue", "distressValue", "valuer", "valuationDate"],
    "INSURANCE": ["insurer", "policyNo", "sumInsured", "validTill", "assignedTo"],
    "RC": ["owner", "vehicleRegNo", "chassisNo", "makeModel", "manufactureYear", "assignedTo"],
    "INVOICE": ["makeModel", "chassisNo"],
}

EC_TYPES = [("SALE", r"sale deed|conveyance|sale"), ("GIFT", r"gift"), ("PARTITION", r"partition"), ("RELEASE", r"release|reconveyance|satisfaction"),
            ("MORTGAGE", r"mortgage|equitable mortgage|deposit of title|memorandum of deposit"), ("LEASE", r"lease"),
            ("AGREEMENT", r"agreement"), ("COURT_ORDER", r"court|decree")]
DATE = r"(\d{1,2}[-/.]\d{1,2}[-/.]\d{4}|\d{4}-\d{2}-\d{2})"
DOCNO = r"(\d{1,6}/\d{4})"


def parse_date(s: str | None) -> str | None:
    if not s:
        return None
    s = s.strip()
    for fmt in ("%d-%m-%Y", "%d/%m/%Y", "%d.%m.%Y", "%Y-%m-%d", "%d %b %Y", "%d %B %Y"):
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            pass
    m = re.search(DATE, s)
    return parse_date(m.group(1)) if m and m.group(1) != s else None


def parse_amount(s: str | None) -> float | None:
    if not s:
        return None
    t = s.lower().replace(",", "").replace("₹", "").replace("rs.", "").replace("rs", "").strip()
    m = re.search(r"(\d+(?:\.\d+)?)\s*(lakh|lac|l|crore|cr)?\b", t)
    if not m:
        return None
    v = float(m.group(1))
    unit = m.group(2)
    if unit in ("lakh", "lac", "l"):
        v *= 100_000
    elif unit in ("crore", "cr"):
        v *= 10_000_000
    return round(v, 2)


def _label_value(lines: list[str]) -> dict[str, tuple[str, int]]:
    """Every 'label: value' (or 'label - value') line, by normalised label, with its line number."""
    out = {}
    for i, ln in enumerate(lines):
        m = re.match(r"\s*([A-Za-z][A-Za-z .&/'()-]{1,40}?)\s*[:\-–]\s+(.+)$", ln)
        if m:
            out.setdefault(m.group(1).strip().lower().rstrip("."), (m.group(2).strip(), i))
    return out


def extract_fields(text: str, doc_type: str, ocr_conf: float = 1.0) -> dict:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    lv = _label_value(lines)
    fields = {}
    wanted = NEEDED.get(doc_type, list(LABELS))
    for key in wanted:
        for syn in LABELS.get(key, []):
            if syn in lv:
                raw = lv[syn][0]
                val: object = raw
                conf = 0.95 * ocr_conf
                if key in ("areaSqft", "marketValue", "realisableValue", "distressValue", "sumInsured"):
                    val = parse_amount(raw)
                    if val is None:
                        conf = 0.4
                elif key in ("registrationDate", "periodFrom", "periodTo", "valuationDate", "validTill"):
                    val = parse_date(raw) or raw
                    if val == raw:
                        conf = 0.5
                elif key == "manufactureYear":
                    m = re.search(r"(19|20)\d{2}", raw)
                    val = int(m.group(0)) if m else raw
                elif key in ("surveyNo", "municipalId", "registrationNo", "chassisNo", "vehicleRegNo", "policyNo"):
                    val = re.sub(r"\s+", "", raw).upper()
                fields[key] = {"value": val, "confidence": round(min(1.0, conf), 3)}
                break
    return fields


def parse_ec(text: str) -> list[dict]:
    """EC transaction rows: date, type, document number, from -> to parties, and the mortgage a release refers to."""
    rows = []
    for ln in text.splitlines():
        d = re.search(DATE, ln)
        n = re.search(DOCNO, ln)
        if not d or not n:
            continue
        kind = None
        for k, pat in EC_TYPES:
            if re.search(pat, ln, re.I):
                kind = k
                break
        if not kind:
            continue
        parties = re.search(r"(?:by|from)?\s*([A-Z][A-Za-z .]+?)\s*(?:->|→|to|in favour of)\s*([A-Z][A-Za-z .&]+?)(?:\s*\||\s*$|\s+\(|,)", ln)
        ref = re.search(r"(?:of|against|ref\.?)\s*(?:doc(?:ument)?\s*no\.?\s*)?(\d{1,6}/\d{4})", ln, re.I)
        nos = re.findall(DOCNO, ln)
        linked = ref.group(1) if kind == "RELEASE" and ref else None
        own = next((x for x in nos if x != linked), n.group(1))
        row = {"date": parse_date(d.group(1)), "type": kind, "docNo": own, "from": None, "to": None, "linkedRef": linked}
        if parties:
            row["from"], row["to"] = parties.group(1).strip(), parties.group(2).strip()
        rows.append(row)
    return rows


def analyze_document(data: bytes, content_type: str, doc_type: str) -> dict:
    ocr = run_ocr(data, content_type)
    text = ocr.text
    conf = 1.0 if ocr.pdf_text.strip() else max(0.3, min(1.0, ocr.mean_conf / 100 if ocr.mean_conf > 1 else ocr.mean_conf))
    fields = extract_fields(text, doc_type, conf)
    out = {"docType": doc_type, "fields": fields, "engine": "text-layer" if ocr.pdf_text.strip() else "ocr",
           "pages": len(ocr.pages) if ocr.pages else 1, "textChars": len(text)}
    if doc_type == "EC":
        out["transactions"] = parse_ec(text)
    needed = NEEDED.get(doc_type, [])
    found = [k for k in needed if k in fields]
    confs = [f["confidence"] for f in fields.values()] or [0.0]
    out["readConfidence"] = round(min(confs) if len(found) == len(needed) and needed else (sum(confs) / len(confs)) * (len(found) / max(1, len(needed))), 3)
    out["missing"] = [k for k in needed if k not in fields]
    out["toConfirm"] = [k for k, f in fields.items() if f["confidence"] < CONFIRM_BELOW]
    out["software"] = ocr.exif_software or (ocr.pdf_meta or {}).get("Producer")
    return out


EDITORS = ("photoshop", "gimp", "snapseed", "lightroom", "picsart", "canva", "pixlr", "facetune")


def analyze_media(data: bytes) -> dict:
    ex = fieldphotos.exif(data)
    try:
        img = Image.open(io.BytesIO(data))
        img.load()
        q = quality.assess(img)
        g = img.convert("L")
        import numpy as np
        bright = float((np.asarray(g) > 250).mean())
    except Exception:  # noqa: BLE001
        return {"exif": ex, "quality": "UNKNOWN", "tamperScore": 0.0, "notes": ["Not a readable image"]}
    label = "GOOD"
    if q["sharpness"] < quality.BLUR_THRESHOLD:
        label = "BLURRY"
    elif q["brightness"] < 50:
        label = "DARK"
    elif bright > 0.25:
        label = "GLARE"
    tamper, notes = 0.0, []
    sw = (ex.get("software") or "").lower()
    if any(e in sw for e in EDITORS):
        tamper = 0.6
        notes.append(f"Edited with {ex['software']}")
    if not ex.get("takenAt"):
        notes.append("No capture time in the photo")
    if ex.get("lat") is None:
        notes.append("No GPS in the photo")
    return {"exif": ex, "quality": label, "sharpness": q["sharpness"], "brightness": q["brightness"], "tamperScore": tamper, "notes": notes}
