"""Loan-plan Document AI: education (admission letter, fee letter, fee demand), farm (7/12 land record, crop or livestock
insurance) and housing (construction estimate, engineer's stage certificate).

Returns {docType, fields:{key:{value, confidence}}, readConfidence, missing, toConfirm, engine}.
"""
import re

from .asset import _pairs
from .collateral import CONFIRM_BELOW, parse_amount, parse_date
from .ocr import run_ocr

LABELS = {
    "student": ["student", "student name", "name of student", "candidate"],
    "institution": ["institution", "institute", "college", "school", "university"],
    "course": ["course", "programme", "program", "class"],
    "startDate": ["course start", "start date", "commencement", "academic session starts"],
    "duration": ["duration", "course duration"],
    "admissionNo": ["admission no", "admission number", "enrolment no", "roll no"],
    "academicYear": ["academic year", "session"],
    "term": ["term", "semester"],
    "amountDue": ["amount due", "fee due", "amount payable"],
    "dueDate": ["due date", "pay by", "last date"],
    "total": ["total", "total fees", "total fee", "grand total", "total estimate", "estimated cost"],
    "owner": ["occupant", "khatedar", "owner", "name of occupant", "occupant name"],
    "surveyNo": ["survey no", "gat no", "gat number", "survey number", "khasra no"],
    "area": ["area", "total area", "cultivable area"],
    "village": ["village", "mouje"],
    "crops": ["crops", "crop", "crop sown"],
    "engineer": ["engineer", "architect", "prepared by", "certified by"],
    "builtUpArea": ["built-up area", "built up area", "builtup area"],
    "carpetArea": ["carpet area"],
    "planNo": ["approved plan no", "building permission no", "plan approval no", "sanctioned plan no"],
    "stage": ["stage", "stage completed", "work completed up to"],
    "percent": ["percent complete", "completion", "work done", "progress"],
    "insurer": ["insurer", "insurance company"],
    "policyNo": ["policy no", "policy number", "application no"],
    "sumInsured": ["sum insured", "insured value"],
    "validTill": ["valid till", "period to", "expiry"],
    "scheme": ["scheme"],
    "lessor": ["lessor", "landlord", "licensor"],
    "lessee": ["lessee", "tenant", "licensee"],
    "monthlyRent": ["monthly rent", "rent per month", "monthly licence fee", "rent"],
    "endDate": ["end date", "lease end", "lease expiry", "expiry of lease"],
    "lockIn": ["lock-in", "lock in", "lock-in period", "lock in period"],
    "escalation": ["escalation", "rent escalation"],
}

NEEDED = {
    "ADMISSION_LETTER": ["student", "institution", "course", "startDate", "duration"],
    "FEE_LETTER": ["student", "institution", "academicYear", "terms", "total"],
    "FEE_DEMAND": ["student", "institution", "term", "amountDue"],
    "LAND_RECORD_712": ["owner", "surveyNo", "area", "village"],
    "CONSTRUCTION_ESTIMATE": ["engineer", "total", "builtUpArea", "carpetArea"],
    "STAGE_CERTIFICATE": ["engineer", "stage", "percent"],
    "INSURANCE": ["insurer", "policyNo", "sumInsured", "validTill"],
    "LEASE_DEED": ["lessor", "lessee", "monthlyRent", "startDate", "endDate", "lockIn"],
}
AMOUNTS = {"total", "amountDue", "sumInsured", "monthlyRent"}
DATES = {"startDate", "dueDate", "validTill", "endDate"}


def _lookup(pairs: dict[str, str], key: str) -> str | None:
    for syn in LABELS.get(key, []):
        if syn in pairs:
            return pairs[syn]
    return None


def _months(raw: str) -> int | None:
    m = re.search(r"(\d+(?:\.\d+)?)\s*(year|yr|month|semester)", raw, re.I)
    if not m:
        return None
    n = float(m.group(1))
    unit = m.group(2).lower()
    return int(round(n * 12)) if unit.startswith(("year", "yr")) else int(round(n * 6)) if unit.startswith("sem") else int(round(n))


def _acres(raw: str) -> float | None:
    m = re.search(r"(\d+(?:\.\d+)?)\s*(ha|hectare|hectares|acre|acres|guntha|gunthas)\b", raw, re.I)
    if not m:
        return None
    n, u = float(m.group(1)), m.group(2).lower()
    if u.startswith("ha") or u.startswith("hect"):
        return round(n * 2.47105, 2)
    if u.startswith("gun"):
        return round(n / 40, 2)
    return round(n, 2)


def extract_fields(text: str, doc_type: str, ocr_conf: float = 1.0) -> dict:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    pairs = _pairs(lines)
    fields: dict[str, dict] = {}

    def put(key, val, conf=0.95):
        if val is not None and val != "":
            fields[key] = {"value": val, "confidence": round(min(1.0, conf * ocr_conf), 3)}

    for key in LABELS:
        raw = _lookup(pairs, key)
        if raw is None:
            continue
        if key in AMOUNTS:
            put(key, parse_amount(raw))
        elif key in DATES:
            v = parse_date(raw)
            put(key, v or raw, 0.95 if v else 0.5)
        elif key == "duration":
            put("durationMonths", _months(raw), 0.9)
        elif key == "lockIn":
            put("lockInMonths", _months(raw), 0.9)
        elif key == "escalation":
            m = re.search(r"(\d{1,2}(?:\.\d+)?)\s*%", raw)
            put("escalationPct", float(m.group(1)) if m else None)
        elif key == "area":
            a = _acres(raw)
            put("areaAcres", a, 0.95 if a is not None else 0.4)
        elif key in ("builtUpArea", "carpetArea"):
            m = re.search(r"(\d[\d,]*(?:\.\d+)?)", raw)
            put(key + "Sqft", float(m.group(1).replace(",", "")) if m else None)
        elif key == "percent":
            m = re.search(r"(\d{1,3}(?:\.\d+)?)\s*%?", raw)
            put("percent", float(m.group(1)) if m else None)
        elif key in ("surveyNo", "policyNo", "planNo", "admissionNo"):
            put(key, re.sub(r"\s+", "", raw).upper())
        else:
            put(key, raw)

    # fee letters: one line per term ("Term 1 fee: 1,60,000")
    terms = []
    for ln in lines:
        m = re.match(r"\s*(term|semester|installment|instalment)\s*(\d+)\s*(fee|fees)?\s*[:–-]\s*(.+)$", ln, re.I)
        if m:
            amt = parse_amount(m.group(4))
            if amt:
                terms.append({"term": int(m.group(2)), "amount": amt})
    if terms:
        put("terms", terms, 0.95)
    if "term" in fields:
        m = re.search(r"(\d+)", str(fields["term"]["value"]))
        fields["term"]["value"] = int(m.group(1)) if m else fields["term"]["value"]
    if doc_type == "FEE_LETTER" and terms and "total" in fields:
        s = sum(t["amount"] for t in terms)
        if abs(s - fields["total"]["value"]) > 1:
            fields["total"]["confidence"] = min(fields["total"]["confidence"], 0.6)
            fields["total"]["note"] = f"Terms add up to {s:,.0f}"
    return fields


def analyze_document(data: bytes, content_type: str, doc_type: str) -> dict:
    ocr = run_ocr(data, content_type)
    text = ocr.text
    text_layer = bool(ocr.pdf_text.strip())
    conf = 1.0 if text_layer else max(0.3, min(1.0, ocr.mean_conf / 100 if ocr.mean_conf > 1 else ocr.mean_conf))
    fields = extract_fields(text, doc_type, conf)
    needed = [("durationMonths" if k == "duration" else "lockInMonths" if k == "lockIn" else "areaAcres" if k == "area" else k + "Sqft" if k in ("builtUpArea", "carpetArea") else k)
              for k in NEEDED.get(doc_type, [])]
    found = [k for k in needed if k in fields]
    confs = [fields[k]["confidence"] for k in found] or [0.0]
    read = min(confs) if needed and len(found) == len(needed) else (sum(confs) / len(confs)) * (len(found) / max(1, len(needed)))
    return {"docType": doc_type, "fields": fields, "engine": "text-layer" if text_layer else "ocr", "readConfidence": round(read, 3),
            "missing": [k for k in needed if k not in fields], "toConfirm": [k for k, f in fields.items() if f["confidence"] < CONFIRM_BELOW],
            "textChars": len(text)}
