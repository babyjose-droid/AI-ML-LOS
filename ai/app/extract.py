"""Field extraction per document type. Works on OCR lines; every field carries a confidence."""
import re
from typing import Callable

from .ocr import OcrResult
from .validators import (AADHAAR_RE, DATE_RE, DL_RE, EPIC_RE, PAN_RE, PASSPORT_RE, UDYAM_RE, aadhaar_valid,
                         mask_aadhaar, pan_valid, parse_date, parse_mrz, plausible_dob)

Field = dict  # {"value": str, "confidence": float, "source": str}

REQUIRED = {
    "PAN": ["pan", "name", "dob"],
    "AADHAAR": ["aadhaarLast4", "name", "dob"],
    "VOTER_ID": ["epicNumber", "name"],
    "DRIVING_LICENCE": ["dlNumber", "name", "dob"],
    "PASSPORT": ["passportNumber", "surname", "givenNames", "dob"],
    "UDYAM": ["udyamNumber", "enterpriseName"],
    "SALARY_SLIP": ["employeeName", "netPay"],
    "BANK_STATEMENT": [],
    "GST_CERT": [],
}

HEADER_WORDS = ("GOVERNMENT", "INDIA", "INCOME TAX", "DEPARTMENT", "PERMANENT", "ACCOUNT", "AADHAAR", "SPECIMEN",
                "ELECTION", "COMMISSION", "DRIVING", "LICENCE", "PASSPORT", "REPUBLIC", "CARD", "UNION")


class Ctx:
    def __init__(self, ocr: OcrResult):
        self.ocr = ocr
        self.lines = ocr.lines
        self.text = ocr.text
        self.from_pdf_text = bool(ocr.pdf_text.strip()) and len(ocr.pdf_text.strip()) > 40
        self._conf: dict[str, float] = {}
        for p in ocr.pages:
            for w in p.words:
                self._conf[w.text.upper()] = max(self._conf.get(w.text.upper(), 0), w.conf)

    def conf(self, value: str) -> float:
        if self.from_pdf_text:
            return 0.95
        toks = [t for t in re.split(r"\s+", value.upper()) if t]
        if not toks:
            return 0.0
        cs = [self._conf.get(t, self._fuzzy(t)) for t in toks]
        return round(sum(cs) / len(cs), 3)

    def _fuzzy(self, tok: str) -> float:
        for k, v in self._conf.items():
            if tok in k or k in tok:
                return v
        return 0.5

    def field(self, value, boost: float = 1.0, source: str = "ocr", raw: str | None = None) -> Field | None:
        if value is None or value == "":
            return None
        v = str(value).strip()
        c = 0.95 if source == "derived" else self.conf(raw or v)
        return {"value": v, "confidence": round(min(0.99, c * boost), 3), "source": "pdf-text" if self.from_pdf_text and source == "ocr" else source}


def _clean_name(s: str) -> str:
    s = re.sub(r"[^A-Za-z .']", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def _after_label(lines: list[str], label: str, same_line_ok=True, exclude: tuple[str, ...] = ()) -> str | None:
    lab = label.upper()
    for i, ln in enumerate(lines):
        up = ln.upper()
        if lab in up and not any(e in up for e in exclude):
            rest = re.split(re.escape(label), ln, flags=re.I, maxsplit=1)[-1].strip(" :-/")
            if same_line_ok and len(rest) >= 2:
                return rest
            if i + 1 < len(lines):
                return lines[i + 1].strip()
    return None


def _fix_pan(s: str) -> str | None:
    """Corrects common OCR confusions using the PAN structure (5 letters, 4 digits, 1 letter)."""
    to_l = {"0": "O", "1": "I", "5": "S", "8": "B", "2": "Z", "6": "G"}
    to_d = {"O": "0", "D": "0", "I": "1", "L": "1", "S": "5", "B": "8", "Z": "2", "G": "6"}
    for tok in re.findall(r"[A-Z0-9]{10}", s.upper().replace(" ", "")):
        c = "".join(to_l.get(ch, ch) for ch in tok[:5]) + "".join(to_d.get(ch, ch) for ch in tok[5:9]) + to_l.get(tok[9], tok[9])
        if pan_valid(c):
            return c
    return None


def _first_date(text: str) -> str | None:
    return parse_date(text)


def _raw_date(s: str | None) -> str | None:
    m = DATE_RE.search(s or "")
    return m.group(0) if m else None


def _raw_money(s: str | None) -> str | None:
    m = re.search(r"[0-9][0-9,]*\.?[0-9]*", s or "")
    return m.group(0) if m else None


def _money(s: str) -> str | None:
    m = re.search(r"([0-9][0-9,]*\.?[0-9]*)", s or "")
    if not m:
        return None
    v = m.group(1).replace(",", "")
    try:
        return str(round(float(v), 2)).rstrip("0").rstrip(".")
    except ValueError:
        return None


# ---------------- per type ----------------

def pan(c: Ctx) -> dict[str, Field]:
    up = c.text.upper()
    m = PAN_RE.search(up)
    number = m.group(1) if m else _fix_pan(up)
    name = _after_label(c.lines, "Name", exclude=("FATHER",))
    if name and name.upper().startswith("FATHER"):
        name = None
    father = _after_label(c.lines, "Father")
    if father:
        father = re.sub(r"(?i)^'?s\s*name", "", father).strip(" :")
    dob_src = _after_label(c.lines, "Date of Birth") or c.text
    dob = parse_date(dob_src)
    out = {
        "pan": c.field(number, 1.0 if m else 0.85),
        "name": c.field(_clean_name(name) if name else None),
        "fatherName": c.field(_clean_name(father) if father else None),
        "dob": c.field(dob, raw=_raw_date(dob_src)),
    }
    if number:
        out["holderType"] = c.field({"P": "Individual", "C": "Company", "H": "HUF", "F": "Firm"}.get(number[3], "Other"), source="derived")
    return out


def aadhaar(c: Ctx) -> dict[str, Field]:
    number, candidate = None, False
    for m in AADHAAR_RE.finditer(c.text):
        n = "".join(m.groups())
        candidate = True
        if aadhaar_valid(n):
            number = n
            break
    dob_line_idx = next((i for i, ln in enumerate(c.lines) if re.search(r"(DOB|Date of Birth|Year of Birth|YOB)", ln, re.I)), None)
    dob = parse_date(c.lines[dob_line_idx]) if dob_line_idx is not None else _first_date(c.text)
    if not dob and dob_line_idx is not None:
        y = re.search(r"(19|20)[0-9]{2}", c.lines[dob_line_idx])
        dob = y.group(0) if y else None
    name = None
    if dob_line_idx:
        for j in range(dob_line_idx - 1, -1, -1):
            cand = _clean_name(c.lines[j])
            if len(cand) >= 3 and not any(h in cand.upper() for h in HEADER_WORDS):
                name = cand
                break
    gender = next((g for g in ("FEMALE", "MALE", "TRANSGENDER") if re.search(rf"\b{g}\b", c.text.upper())), None)
    addr = None
    idx = next((i for i, ln in enumerate(c.lines) if ln.upper().startswith("ADDRESS")), None)
    if idx is not None:
        parts = [re.sub(r"(?i)^address\s*:?", "", c.lines[idx]).strip()]
        for ln in c.lines[idx + 1: idx + 6]:
            if AADHAAR_RE.search(ln):
                break
            parts.append(ln)
            if re.search(r"\b[1-9][0-9]{5}\b", ln):
                break
        addr = ", ".join(p for p in parts if p)
    pin = re.search(r"\b([1-9][0-9]{5})\b", addr or "")
    out = {
        "aadhaarMasked": c.field(mask_aadhaar(number) if number else None, source="derived"),
        "aadhaarLast4": c.field(number[-4:] if number else None, source="derived"),
        "name": c.field(name),
        "dob": c.field(dob, raw=_raw_date(c.lines[dob_line_idx]) if dob_line_idx is not None else None),
        "gender": c.field(gender.title() if gender else None),
        "address": c.field(addr),
        "pincode": c.field(pin.group(1) if pin else None),
    }
    if candidate:
        out["aadhaarChecksum"] = {"value": "ok" if number else "fail", "confidence": 0.99, "source": "derived"}
    if number:  # confidence of the derived number follows the OCR confidence of its digit groups
        conf = c.conf(f"{number[0:4]} {number[4:8]} {number[8:12]}")
        out["aadhaarMasked"]["confidence"] = out["aadhaarLast4"]["confidence"] = conf
    return out


def voter(c: Ctx) -> dict[str, Field]:
    m = EPIC_RE.search(c.text.upper())
    name = _after_label(c.lines, "Elector's Name") or _after_label(c.lines, "Name", exclude=("FATHER", "HUSBAND"))
    rel = _after_label(c.lines, "Father's Name") or _after_label(c.lines, "Husband's Name")
    return {
        "epicNumber": c.field(m.group(1) if m else None),
        "name": c.field(_clean_name(name) if name else None),
        "relationName": c.field(_clean_name(rel) if rel else None),
        "dob": c.field(parse_date(_after_label(c.lines, "Date of Birth") or c.text), raw=_raw_date(_after_label(c.lines, "Date of Birth") or c.text)),
    }


def driving_licence(c: Ctx) -> dict[str, Field]:
    m = DL_RE.search(c.text.upper())
    name = _after_label(c.lines, "Name", exclude=("FATHER", "S/D/W"))
    dob_src = _after_label(c.lines, "DOB") or _after_label(c.lines, "Date of Birth") or ""
    valid_src = _after_label(c.lines, "Valid Till") or ""
    dob, valid = parse_date(dob_src), parse_date(valid_src)
    return {
        "dlNumber": c.field(re.sub(r"[\s-]", "", m.group(1)) if m else None),
        "name": c.field(_clean_name(name) if name else None),
        "dob": c.field(dob, raw=_raw_date(dob_src)),
        "validTill": c.field(valid, raw=_raw_date(valid_src)),
        "address": c.field(_after_label(c.lines, "Address")),
    }


def passport(c: Ctx) -> dict[str, Field]:
    mrz = parse_mrz(c.lines)
    out: dict[str, Field | None] = {}
    if mrz:
        ok = all(mrz["checks"].values())
        boost = 1.0 if ok else 0.6
        out = {
            "passportNumber": c.field(mrz["number"], boost),
            "surname": c.field(mrz["surname"], boost),
            "givenNames": c.field(mrz["givenNames"], boost),
            "dob": c.field(mrz["dob"], boost, source="derived"),
            "expiry": c.field(mrz["expiry"], boost, source="derived"),
            "sex": c.field(mrz["sex"], boost, source="derived"),
            "nationality": c.field(mrz["nationality"], boost, source="derived"),
        }
        out["mrzChecks"] = {"value": ",".join(f"{k}:{'ok' if v else 'fail'}" for k, v in mrz["checks"].items()),
                            "confidence": 0.99 if ok else 0.3, "source": "derived"}
    else:
        m = PASSPORT_RE.search(c.text.upper())
        out = {
            "passportNumber": c.field(m.group(1) if m else None, 0.8),
            "surname": c.field(_after_label(c.lines, "Surname")),
            "givenNames": c.field(_after_label(c.lines, "Given Name")),
            "dob": c.field(_first_date(c.text)),
        }
    return out


def udyam(c: Ctx) -> dict[str, Field]:
    m = UDYAM_RE.search(c.text.upper().replace(" ", "")) or UDYAM_RE.search(c.text.upper())
    ent = _after_label(c.lines, "NAME OF ENTERPRISE")
    owner = _after_label(c.lines, "NAME OF ENTREPRENEUR") or _after_label(c.lines, "NAME OF OWNER")
    typ = _after_label(c.lines, "TYPE OF ENTERPRISE")
    return {
        "udyamNumber": c.field(m.group(1) if m else None),
        "enterpriseName": c.field(ent),
        "ownerName": c.field(_clean_name(owner) if owner else None),
        "enterpriseType": c.field(typ),
    }


def salary(c: Ctx) -> dict[str, Field]:
    name = _after_label(c.lines, "Employee Name") or _after_label(c.lines, "Name")
    net_src = _after_label(c.lines, "Net Pay") or _after_label(c.lines, "Net Salary") or ""
    gross_src = _after_label(c.lines, "Gross Earnings") or _after_label(c.lines, "Gross") or ""
    net, gross = _money(net_src), _money(gross_src)
    month = re.search(r"(?i)(January|February|March|April|May|June|July|August|September|October|November|December)\s+(20[0-9]{2})", c.text)
    return {
        "employeeName": c.field(_clean_name(name) if name else None),
        "employer": c.field(c.lines[0] if c.lines else None, 0.8),
        "netPay": c.field(net, raw=_raw_money(net_src)),
        "grossPay": c.field(gross, raw=_raw_money(gross_src)),
        "month": c.field(month.group(0) if month else None),
    }


EXTRACTORS: dict[str, Callable[[Ctx], dict]] = {
    "PAN": pan, "AADHAAR": aadhaar, "VOTER_ID": voter, "DRIVING_LICENCE": driving_licence,
    "PASSPORT": passport, "UDYAM": udyam, "SALARY_SLIP": salary,
}


def extract(doc_type: str, ocr: OcrResult) -> dict[str, Field]:
    fn = EXTRACTORS.get(doc_type)
    if not fn:
        return {}
    fields = fn(Ctx(ocr))
    return {k: v for k, v in fields.items() if v}


def validations(doc_type: str, fields: dict[str, Field]) -> list[dict]:
    """Structural checks on the extracted values (a failed check sends the document to review)."""
    v = lambda k: (fields.get(k) or {}).get("value")  # noqa: E731
    out = []
    if doc_type == "PAN" and v("pan"):
        out.append({"check": "PAN format", "pass": pan_valid(v("pan"))})
        out.append({"check": "PAN is for an individual", "pass": v("pan")[3] == "P"})
    if doc_type == "AADHAAR":
        out.append({"check": "Aadhaar number found and checksum valid (Verhoeff)", "pass": v("aadhaarChecksum") == "ok"})
    if doc_type == "PASSPORT" and fields.get("mrzChecks"):
        out.append({"check": "Passport MRZ check digits", "pass": "fail" not in v("mrzChecks")})
    if v("dob") and len(v("dob")) == 10:
        out.append({"check": "Date of birth plausible", "pass": plausible_dob(v("dob"))})
    if doc_type == "DRIVING_LICENCE" and v("validTill"):
        from datetime import date
        out.append({"check": "Licence not expired", "pass": v("validTill") >= date.today().isoformat()})
    if doc_type == "PASSPORT" and v("expiry"):
        from datetime import date
        out.append({"check": "Passport not expired", "pass": v("expiry") >= date.today().isoformat()})
    return out
