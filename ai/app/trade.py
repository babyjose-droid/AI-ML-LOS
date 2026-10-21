"""Trade finance Document AI: business invoices offered for financing (receivables, vendor and dealer finance).

Returns {docType, fields:{key:{value, confidence}}, readConfidence, missing, toConfirm, engine}.
Reads the seller's and the buyer's GSTIN separately, the due date (or derives it from the payment terms) and the PO number.
"""
import re
from datetime import date, timedelta

from .asset import GSTIN, _pairs
from .collateral import CONFIRM_BELOW, parse_amount, parse_date
from .ocr import run_ocr

LABELS = {
    "invoiceNo": ["invoice no", "tax invoice no", "invoice number", "bill no"],
    "date": ["invoice date", "date", "dated"],
    "dueDate": ["due date", "payment due", "payment due date", "due on"],
    "terms": ["payment terms", "terms", "credit period"],
    "poNo": ["po no", "purchase order no", "po number", "order no", "buyer po"],
    "sellerName": ["supplier", "seller", "from", "sold by"],
    "sellerGstin": ["supplier gstin", "seller gstin", "gstin of supplier", "our gstin"],
    "buyerName": ["buyer", "bill to", "billed to", "recipient", "sold to"],
    "buyerGstin": ["buyer gstin", "recipient gstin", "gstin of recipient", "customer gstin"],
    "basePrice": ["taxable value", "base price", "basic value", "sub total", "subtotal"],
    "cgst": ["cgst"], "sgst": ["sgst", "utgst"], "igst": ["igst"],
    "gst": ["gst", "total gst", "gst amount"],
    "total": ["invoice total", "grand total", "total", "total amount", "amount payable", "invoice value"],
}
NEEDED = ["invoiceNo", "date", "dueDate", "sellerGstin", "buyerGstin", "basePrice", "gst", "total"]
AMOUNTS = {"basePrice", "cgst", "sgst", "igst", "gst", "total"}


def _gstin(raw: str) -> tuple[str, float]:
    s = re.sub(r"\s+", "", raw).upper()
    m = GSTIN.search(s)
    return (m.group(0), 0.95) if m else (s, 0.5)


def extract_fields(text: str, ocr_conf: float = 1.0) -> dict:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    pairs = _pairs(lines)
    fields: dict[str, dict] = {}

    def put(key, val, conf=0.95):
        if val is not None and val != "":
            fields[key] = {"value": val, "confidence": round(min(1.0, conf * ocr_conf), 3)}

    for key in LABELS:
        raw = _lookup_t(pairs, key)
        if raw is None:
            continue
        if key in AMOUNTS:
            put(key, parse_amount(raw))
        elif key in ("date", "dueDate"):
            v = parse_date(raw)
            put(key, v or raw, 0.95 if v else 0.5)
        elif key in ("sellerGstin", "buyerGstin"):
            put(key, *_gstin(raw))
        elif key in ("invoiceNo", "poNo"):
            put(key, re.sub(r"\s+", "", raw).upper())
        else:
            put(key, raw)

    # GSTINs printed without a label: the first is the seller's, the second the buyer's
    found = list(dict.fromkeys(GSTIN.findall(text.upper())))
    if "sellerGstin" not in fields and found:
        put("sellerGstin", found[0], 0.8)
    if "buyerGstin" not in fields:
        rest = [g for g in found if g != fields.get("sellerGstin", {}).get("value")]
        if rest:
            put("buyerGstin", rest[0], 0.8)

    parts = [fields[k]["value"] for k in ("cgst", "sgst", "igst") if k in fields]
    if parts and "gst" not in fields:
        put("gst", round(sum(parts), 2), 0.9)
    for k in ("cgst", "sgst", "igst"):
        fields.pop(k, None)

    # due date from the payment terms ("60 days") when not printed
    if "dueDate" not in fields and "terms" in fields and isinstance(fields.get("date", {}).get("value"), str):
        m = re.search(r"(\d{1,3})\s*days", str(fields["terms"]["value"]), re.I)
        try:
            d = date.fromisoformat(fields["date"]["value"])
            if m:
                put("dueDate", (d + timedelta(days=int(m.group(1)))).isoformat(), 0.85)
        except ValueError:
            pass
    if "terms" in fields:
        m = re.search(r"(\d{1,3})\s*days", str(fields["terms"]["value"]), re.I)
        fields["terms"]["value"] = int(m.group(1)) if m else fields["terms"]["value"]

    if all(k in fields for k in ("basePrice", "gst", "total")):
        calc = fields["basePrice"]["value"] + fields["gst"]["value"]
        if abs(calc - fields["total"]["value"]) > max(2.0, 0.005 * fields["total"]["value"]):
            fields["total"]["confidence"] = min(fields["total"]["confidence"], 0.6)
            fields["total"]["note"] = f"Taxable value + GST = {calc:,.0f}, printed total differs"
    return fields


def _lookup_t(pairs: dict[str, str], key: str) -> str | None:
    for syn in LABELS.get(key, []):
        if syn in pairs:
            return pairs[syn]
    return None


def analyze_invoice(data: bytes, content_type: str) -> dict:
    ocr = run_ocr(data, content_type)
    text = ocr.text
    text_layer = bool(ocr.pdf_text.strip())
    conf = 1.0 if text_layer else max(0.3, min(1.0, ocr.mean_conf / 100 if ocr.mean_conf > 1 else ocr.mean_conf))
    fields = extract_fields(text, conf)
    found = [k for k in NEEDED if k in fields]
    confs = [fields[k]["confidence"] for k in found] or [0.0]
    read = min(confs) if len(found) == len(NEEDED) else (sum(confs) / len(confs)) * (len(found) / len(NEEDED))
    return {"docType": "TRADE_INVOICE", "fields": fields, "engine": "text-layer" if text_layer else "ocr",
            "readConfidence": round(read, 3), "missing": [k for k in NEEDED if k not in fields],
            "toConfirm": [k for k, f in fields.items() if f["confidence"] < CONFIRM_BELOW], "textChars": len(text)}
