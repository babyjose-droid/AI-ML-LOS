"""Asset finance Document AI: dealer quotations and invoices, down-payment receipts, insurance and RC.

Returns {docType, fields:{key:{value, confidence}}, readConfidence, missing, toConfirm, engine}.
Amounts are rupees (float), dates ISO, identifiers upper-case without spaces.
"""
import re

from .collateral import CONFIRM_BELOW, parse_amount, parse_date
from .ocr import run_ocr

LABELS = {
    "quoteNo": ["quotation no", "quote no", "quotation number", "proforma no", "proforma invoice no", "ref no"],
    "invoiceNo": ["invoice no", "tax invoice no", "invoice number", "bill no"],
    "date": ["date", "invoice date", "quotation date", "dated"],
    "gstin": ["gstin", "dealer gstin", "supplier gstin", "gstin/uin", "gst no"],
    "make": ["make", "manufacturer", "brand", "oem"],
    "model": ["model", "model / variant", "model/variant", "description"],
    "serialNo": ["chassis no", "chassis number", "vin", "serial no", "serial number", "machine sl no", "frame no"],
    "engineNo": ["engine no", "engine number", "motor no"],
    "basePrice": ["base price", "ex-showroom price", "ex showroom price", "taxable value", "basic price", "price"],
    "cgst": ["cgst"],
    "sgst": ["sgst", "utgst"],
    "igst": ["igst"],
    "gst": ["gst", "gst amount", "total gst"],
    "otherCharges": ["other charges", "accessories", "handling charges", "installation charges", "freight"],
    "total": ["total", "grand total", "invoice total", "total amount", "invoice value", "on-road price", "amount payable"],
    "amount": ["amount received", "amount", "received amount", "down payment", "margin money"],
    "receiptNo": ["receipt no", "receipt number", "money receipt no"],
    "insurer": ["insurer", "insurance company", "issued by"],
    "policyNo": ["policy no", "policy number", "certificate no"],
    "insuredValue": ["idv", "insured declared value", "sum insured", "insured value"],
    "validTill": ["valid till", "period of insurance to", "expiry", "valid upto", "policy end date", "to"],
    "hypothecation": ["hypothecation", "hypothecated to", "financier", "hpa with", "hypothecation with"],
    "registrationNo": ["registration no", "regn no", "registration number", "vehicle no"],
    "owner": ["owner", "registered owner", "owner name"],
}

NEEDED = {
    "QUOTATION": ["quoteNo", "date", "gstin", "make", "model", "total"],
    "INVOICE": ["invoiceNo", "date", "gstin", "make", "model", "serialNo", "basePrice", "gst", "total"],
    "DOWN_PAYMENT_RECEIPT": ["receiptNo", "date", "amount"],
    "INSURANCE": ["insurer", "policyNo", "insuredValue", "validTill", "hypothecated"],
    "INSURANCE_HP": ["insurer", "policyNo", "hypothecated"],
    "RC_HP": ["registrationNo", "serialNo", "hypothecated"],
}
AMOUNTS = {"basePrice", "cgst", "sgst", "igst", "gst", "otherCharges", "total", "amount", "insuredValue"}
DATES = {"date", "validTill"}
IDS = {"serialNo", "engineNo", "registrationNo", "policyNo", "gstin", "quoteNo", "invoiceNo", "receiptNo"}
GSTIN = re.compile(r"\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b")


def _pairs(lines: list[str]) -> dict[str, str]:
    """'label: value' pairs; a line may hold several ('Make: X   Model: Y') separated by two spaces or '|'."""
    out: dict[str, str] = {}
    for ln in lines:
        for part in re.split(r"\s{3,}|\s\|\s", ln):
            m = re.match(r"\s*([A-Za-z][A-Za-z .&/'()-]{0,40}?)\s*[:–]\s*(.+)$", part)
            if m:
                out.setdefault(m.group(1).strip().lower().rstrip("."), m.group(2).strip())
    return out


def _lookup(pairs: dict[str, str], key: str) -> str | None:
    for syn in LABELS.get(key, []):
        if syn in pairs:
            return pairs[syn]
    return None


def extract_fields(text: str, doc_type: str, ocr_conf: float = 1.0) -> dict:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    pairs = _pairs(lines)
    fields: dict[str, dict] = {}

    def put(key, val, conf=0.95):
        if val is not None and val != "":
            fields[key] = {"value": val, "confidence": round(min(1.0, conf * ocr_conf), 3)}

    keys = ["quoteNo", "invoiceNo", "date", "gstin", "make", "model", "serialNo", "engineNo", "basePrice", "cgst", "sgst",
            "igst", "gst", "otherCharges", "total", "amount", "receiptNo", "insurer", "policyNo", "insuredValue", "validTill",
            "registrationNo", "owner"]
    for key in keys:
        raw = _lookup(pairs, key)
        if raw is None:
            continue
        if key in AMOUNTS:
            put(key, parse_amount(raw))
        elif key in DATES:
            v = parse_date(raw)
            put(key, v or raw, 0.95 if v else 0.5)
        elif key == "gstin":
            m = GSTIN.search(re.sub(r"\s+", "", raw).upper())
            put(key, m.group(0) if m else re.sub(r"\s+", "", raw).upper(), 0.95 if m else 0.5)
        elif key in IDS:
            put(key, re.sub(r"\s+", "", raw).upper())
        else:
            put(key, raw)

    if "gstin" not in fields:
        m = GSTIN.search(text.upper())
        if m:
            put("gstin", m.group(0), 0.85)

    # GST: explicit total, else the sum of CGST + SGST + IGST.
    parts = [fields[k]["value"] for k in ("cgst", "sgst", "igst") if k in fields]
    if parts and "gst" not in fields:
        put("gst", round(sum(parts), 2), 0.9)
    for k in ("cgst", "sgst", "igst"):
        fields.pop(k, None)

    # A quotation's number may be printed as 'Ref No'; an invoice's quote is not its own number.
    if doc_type == "QUOTATION" and "quoteNo" not in fields and "invoiceNo" in fields:
        fields["quoteNo"] = fields.pop("invoiceNo")

    # Totals that do not add up are marked for confirmation rather than trusted.
    if all(k in fields for k in ("basePrice", "gst", "total")):
        calc = fields["basePrice"]["value"] + fields["gst"]["value"] + fields.get("otherCharges", {"value": 0})["value"]
        if abs(calc - fields["total"]["value"]) > max(2.0, 0.005 * fields["total"]["value"]):
            fields["total"]["confidence"] = min(fields["total"]["confidence"], 0.6)
            fields["total"]["note"] = f"Base + GST + other = {calc:,.0f}, printed total differs"

    if doc_type in ("INSURANCE", "INSURANCE_HP", "RC_HP"):
        hyp = _lookup(pairs, "hypothecation")
        flag = bool(hyp and not re.match(r"^(nil|none|na|n/a|-)$", hyp.strip(), re.I)) or bool(
            re.search(r"hypothecat\w* (?:to|with|in favour of)\s+\S", text, re.I))
        put("hypothecated", flag, 0.9)
        if hyp and flag:
            put("financier", hyp)
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
    return {"docType": doc_type, "fields": fields, "engine": "text-layer" if text_layer else "ocr",
            "readConfidence": round(read, 3), "missing": [k for k in needed if k not in fields],
            "toConfirm": [k for k, f in fields.items() if f["confidence"] < CONFIRM_BELOW], "textChars": len(text)}
