"""Pledge Document AI: consolidated account statements (CAS) of securities, fixed-deposit receipts and lien confirmations.

CAS rows carry an ISIN and a quantity on one line; the holder, depository and BO / client ID are label: value lines.
Returns {docType, fields:{key:{value, confidence}}, readConfidence, missing, toConfirm, engine}; CAS holdings are in fields.holdings.
"""
import re

from .asset import _pairs
from .collateral import CONFIRM_BELOW, parse_amount, parse_date
from .ocr import run_ocr

ISIN = re.compile(r"\b(IN[EF0-9][A-Z0-9]{8}\d)\b")
LABELS = {
    "holder": ["holder", "investor", "name", "first holder", "depositor", "account holder", "pledgor"],
    "depository": ["depository"],
    "dematId": ["bo id", "client id", "demat account", "dp id-client id", "beneficiary id"],
    "issuer": ["issuer", "bank", "issued by", "company"],
    "depositNo": ["fd no", "deposit no", "receipt no", "fixed deposit no", "account no"],
    "principal": ["principal", "deposit amount", "amount deposited", "principal amount"],
    "rate": ["rate of interest", "interest rate", "rate"],
    "startDate": ["date of deposit", "deposit date", "start date", "value date"],
    "maturityDate": ["maturity date", "date of maturity", "matures on"],
    "lienRef": ["pledge sequence no", "psn", "lien ref", "lien reference", "lien no", "pledge ref"],
    "lienDate": ["lien date", "date of lien", "pledge date"],
    "pledgee": ["pledgee", "lien in favour of", "in favour of", "lender"],
}
NEEDED = {"CAS_STATEMENT": ["holder", "depository", "dematId", "holdings"],
          "FD_RECEIPT": ["issuer", "depositNo", "holder", "principal", "rate", "maturityDate"],
          "LIEN_CONFIRMATION": ["lienRef", "pledgee"]}


def extract_fields(text: str, doc_type: str, ocr_conf: float = 1.0) -> dict:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    pairs = _pairs(lines)
    fields: dict[str, dict] = {}

    def put(key, val, conf=0.95):
        if val is not None and val != "":
            fields[key] = {"value": val, "confidence": round(min(1.0, conf * ocr_conf), 3)}

    for key, syns in LABELS.items():
        raw = next((pairs[s] for s in syns if s in pairs), None)
        if raw is None:
            continue
        if key == "principal":
            put(key, parse_amount(raw))
        elif key == "rate":
            m = re.search(r"(\d{1,2}(?:\.\d+)?)\s*%?", raw)
            put(key, float(m.group(1)) if m else None)
        elif key in ("startDate", "maturityDate", "lienDate"):
            v = parse_date(raw)
            put(key, v or raw, 0.95 if v else 0.5)
        elif key == "depository":
            m = re.search(r"\b(CDSL|NSDL)\b", raw.upper())
            put(key, m.group(1) if m else raw.upper())
        elif key in ("dematId", "depositNo", "lienRef"):
            put(key, re.sub(r"\s+", "", raw).upper())
        else:
            put(key, raw)
    if "depository" not in fields:
        m = re.search(r"\b(CDSL|NSDL)\b", text.upper())
        if m:
            put("depository", m.group(1), 0.85)

    if doc_type == "CAS_STATEMENT":
        rows = []
        for ln in lines:
            m = ISIN.search(ln)
            if not m:
                continue
            rest = ln[m.end():]
            nums = re.findall(r"(?<![\w.])(\d[\d,]*(?:\.\d+)?)(?![\w])", rest)
            qty = None
            q = re.search(r"(?:qty|quantity|units|free balance)\s*[:\-]?\s*(\d[\d,]*(?:\.\d+)?)", rest, re.I)
            if q:
                qty = float(q.group(1).replace(",", ""))
            elif nums:
                qty = float(nums[0].replace(",", ""))
            name = re.split(r"\s{2,}|\s+(?:qty|quantity|units)\b", rest.strip(" :-|"), flags=re.I)[0].strip(" :-|")
            if qty:
                rows.append({"isin": m.group(1), "name": name, "quantity": qty})
        if rows:
            put("holdings", rows, 0.95)
    return fields


def analyze_document(data: bytes, content_type: str, doc_type: str) -> dict:
    ocr = run_ocr(data, content_type)
    text = ocr.text
    text_layer = bool(ocr.pdf_text.strip())
    conf = 1.0 if text_layer else max(0.3, min(1.0, ocr.mean_conf / 100 if ocr.mean_conf > 1 else ocr.mean_conf))
    fields = extract_fields(text, doc_type, conf)
    needed = NEEDED.get(doc_type, [])
    found = [k for k in needed if k in fields]
    confs = [fields[k]["confidence"] for k in found] or [0.0]
    read = min(confs) if needed and len(found) == len(needed) else (sum(confs) / len(confs)) * (len(found) / max(1, len(needed)))
    return {"docType": doc_type, "fields": fields, "engine": "text-layer" if text_layer else "ocr", "readConfidence": round(read, 3),
            "missing": [k for k in needed if k not in fields], "toConfirm": [k for k, f in fields.items() if f["confidence"] < CONFIRM_BELOW],
            "textChars": len(text)}
