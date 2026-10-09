"""Document type classification from OCR text: keyword evidence plus number-pattern evidence."""
import re

from .validators import AADHAAR_RE, DL_RE, EPIC_RE, GSTIN_RE, PAN_RE, UDYAM_RE, aadhaar_valid

TYPES = ["PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "UDYAM", "SALARY_SLIP", "BANK_STATEMENT", "GST_CERT"]

KEYWORDS: dict[str, list[tuple[str, float]]] = {
    "PAN": [("INCOME TAX DEPARTMENT", 3), ("PERMANENT ACCOUNT NUMBER", 3), ("INCOME TAX", 1.5)],
    "AADHAAR": [("AADHAAR", 3), ("UNIQUE IDENTIFICATION", 3), ("GOVERNMENT OF INDIA", 1), ("MERA AADHAAR", 2), ("VID", 0.5)],
    "VOTER_ID": [("ELECTION COMMISSION", 3), ("ELECTOR", 2), ("IDENTITY CARD", 0.5)],
    "DRIVING_LICENCE": [("DRIVING LICENCE", 3), ("DRIVING LICENSE", 3), ("TRANSPORT", 1), ("VALID TILL", 1), ("DL NO", 2)],
    "PASSPORT": [("PASSPORT", 3), ("REPUBLIC OF INDIA", 2), ("P<IND", 3)],
    "UDYAM": [("UDYAM REGISTRATION", 3), ("UDYAM", 2), ("ENTERPRISE", 1)],
    "SALARY_SLIP": [("SALARY SLIP", 3), ("PAY SLIP", 3), ("PAYSLIP", 3), ("NET PAY", 2), ("GROSS EARNINGS", 1.5), ("DEDUCTIONS", 1)],
    "BANK_STATEMENT": [("STATEMENT OF ACCOUNT", 3), ("ACCOUNT STATEMENT", 3), ("OPENING BALANCE", 2), ("CLOSING BALANCE", 2), ("IFSC", 1), ("WITHDRAWAL", 1)],
    "GST_CERT": [("GOODS AND SERVICES TAX", 3), ("REGISTRATION CERTIFICATE", 1.5), ("GSTIN", 2), ("FORM GST REG-06", 3)],
}


def classify(text: str) -> tuple[str, float, dict[str, float]]:
    up = text.upper()
    scores = {t: 0.0 for t in TYPES}
    for t, kws in KEYWORDS.items():
        for kw, w in kws:
            if kw in up:
                scores[t] += w
    if PAN_RE.search(up):
        scores["PAN"] += 2
    for m in AADHAAR_RE.finditer(text):
        if aadhaar_valid("".join(m.groups())):
            scores["AADHAAR"] += 3
            break
    if EPIC_RE.search(up):
        scores["VOTER_ID"] += 1.5
    if DL_RE.search(up):
        scores["DRIVING_LICENCE"] += 2
    if re.search(r"P<IND[A-Z<]{10,}", up.replace(" ", "")):
        scores["PASSPORT"] += 3
    if UDYAM_RE.search(up):
        scores["UDYAM"] += 3
    if GSTIN_RE.search(up) and scores["GST_CERT"] > 0:
        scores["GST_CERT"] += 1
    # PAN numbers appear on many documents; only count PAN when PAN keywords are present too
    if scores["PAN"] < 3:
        scores["PAN"] = min(scores["PAN"], 1.5)
    best = max(scores, key=scores.get)
    total = sum(scores.values())
    if scores[best] < 2:
        return "UNKNOWN", 0.0, scores
    second = sorted(scores.values())[-2]
    conf = min(0.99, 0.5 + 0.5 * (scores[best] - second) / max(scores[best], 1e-9)) if total else 0.0
    return best, round(conf, 3), scores
