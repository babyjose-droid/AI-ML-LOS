"""
Bank statement reader: works for any bank by finding the transaction table from its column headings.

1. Words with positions come from the PDF text layer (pdfplumber) or, for scans, from OCR.
2. The header row is found by column keywords (Date, Narration/Description/Particulars, Debit/Withdrawal,
   Credit/Deposit, Amount + Dr/Cr, Balance). Numbers are assigned to columns by their right edge.
3. A line that starts with a date starts a transaction; other lines in the table continue its narration.
4. Every row is reconciled: previous balance + credit - debit must equal the printed balance.
"""
import io
import re
from dataclasses import dataclass, field
from datetime import date

DATE_PATTERNS = [
    (re.compile(r"^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})$"), "dmy4"),
    (re.compile(r"^(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2})$"), "dmy2"),
    (re.compile(r"^(\d{1,2})[\-\s]?([A-Za-z]{3})[\-\s,]?(\d{2,4})$"), "dmony"),
]
MONTHS = {m: i + 1 for i, m in enumerate(["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"])}
AMOUNT_RE = re.compile(r"^-?[\d,]*\d\.\d{2}(?:\s?(?:CR|DR|Cr|Dr|cr|dr))?$")

COLUMN_WORDS = {
    "date": ["date", "txn date", "tran date", "transaction date", "posting date", "trans date"],
    "valuedate": ["value dt", "value date", "val dt"],
    "narration": ["narration", "description", "particulars", "details", "remarks", "transaction details", "transaction remarks"],
    "ref": ["chq", "ref", "cheque", "reference", "chq./ref.no.", "chq no", "instrument"],
    "debit": ["withdrawal", "withdrawals", "debit", "debits", "dr amount", "withdrawal amt", "debit amount", "paid out"],
    "credit": ["deposit", "deposits", "credit", "credits", "cr amount", "deposit amt", "credit amount", "paid in"],
    "amount": ["amount", "txn amount", "transaction amount"],
    "drcr": ["dr/cr", "cr/dr", "type", "dr / cr"],
    "balance": ["balance", "closing balance", "running balance", "available balance", "bal"],
}
FOOTER_WORDS = ("closing balance", "end of statement", "statement summary", "total", "opening balance", "grand total",
                "this is a computer generated", "page total")


@dataclass
class W:
    text: str
    x0: float
    x1: float
    top: float
    font: str = ""


@dataclass
class Line:
    page: int
    top: float
    words: list[W]

    @property
    def text(self) -> str:
        return " ".join(w.text for w in self.words)


@dataclass
class Row:
    date: str
    narration: str = ""
    ref: str = ""
    debit: float = 0.0
    credit: float = 0.0
    balance: float | None = None
    page: int = 0
    fonts: set = field(default_factory=set)
    direction_inferred: bool = False


def parse_date(tok: str) -> str | None:
    t = tok.strip().strip(",")
    for rx, kind in DATE_PATTERNS:
        m = rx.match(t)
        if not m:
            continue
        try:
            if kind == "dmy4":
                return date(int(m.group(3)), int(m.group(2)), int(m.group(1))).isoformat()
            if kind == "dmy2":
                return date(2000 + int(m.group(3)), int(m.group(2)), int(m.group(1))).isoformat()
            mon = MONTHS.get(m.group(2).upper())
            if not mon:
                return None
            y = int(m.group(3))
            y = y + 2000 if y < 100 else y
            return date(y, mon, int(m.group(1))).isoformat()
        except ValueError:
            return None
    return None


def parse_amount(tok: str) -> tuple[float, str | None] | None:
    t = tok.replace(" ", "")
    if not AMOUNT_RE.match(t):
        return None
    sign = None
    up = t.upper()
    if up.endswith("CR"):
        sign, t = "CR", t[:-2]
    elif up.endswith("DR"):
        sign, t = "DR", t[:-2]
    try:
        return float(t.replace(",", "")), sign
    except ValueError:
        return None


# ---------------------------------------------------------------- words

def words_from_pdf(data: bytes, password: str | None) -> tuple[list[Line], dict, bool]:
    """Lines from the PDF text layer. Returns (lines, metadata, has_text)."""
    import pdfplumber
    lines: list[Line] = []
    meta = {}
    with pdfplumber.open(io.BytesIO(data), password=password or "") as pdf:
        meta = {k: str(v) for k, v in (pdf.metadata or {}).items()}
        for pi, page in enumerate(pdf.pages):
            ws = page.extract_words(keep_blank_chars=False, use_text_flow=False, extra_attrs=["fontname"], x_tolerance=1.5)
            lines.extend(_group([W(w["text"], w["x0"], w["x1"], w["top"], w.get("fontname", "")) for w in ws], pi))
    has_text = sum(len(l.words) for l in lines) > 30
    return lines, meta, has_text


def words_from_images(images) -> list[Line]:
    """Lines from OCR, reusing the KYC OCR pipeline (orientation, lighting and skew correction)."""
    from PIL import Image, ImageOps
    from .ocr import deskew_angle, ocr_page
    out: list[Line] = []
    for pi, img in enumerate(images):
        # table rows must be level for line grouping: always straighten small tilts first
        g = ImageOps.grayscale(img)
        g = g.resize((1600, int(g.height * 1600 / g.width))) if g.width > 1600 else g
        ang = deskew_angle(g)
        if ang:
            img = img.rotate(ang, expand=True, fillcolor=(255, 255, 255), resample=Image.BICUBIC)
        page = ocr_page(img)
        ws = [W(w.text, w.box[0], w.box[0] + w.box[2], w.box[1] + w.box[3] / 2) for w in page.words]
        out.extend(_group(ws, pi, tol=max(6, int(page.image.height / 400))))
    return out


def _group(ws: list[W], page: int, tol: float = 3.0) -> list[Line]:
    ws = sorted(ws, key=lambda w: (round(w.top), w.x0))
    lines: list[Line] = []
    for w in ws:
        if lines and abs(lines[-1].top - w.top) <= tol:
            lines[-1].words.append(w)
        else:
            lines.append(Line(page, w.top, [w]))
    for l in lines:
        l.words.sort(key=lambda w: w.x0)
        l.words = _merge_split_amounts(l.words)
    return lines


def _merge_split_amounts(ws: list[W]) -> list[W]:
    """OCR sometimes splits '1,23,456.00' into '1,23,' and '456.00', or a Cr suffix into its own word."""
    out: list[W] = []
    for w in ws:
        if out and re.fullmatch(r"(?i)cr|dr", w.text) and parse_amount(out[-1].text):
            out[-1] = W(out[-1].text + " " + w.text, out[-1].x0, w.x1, out[-1].top, out[-1].font)
            continue
        if out and re.fullmatch(r"[\d,]+,", out[-1].text) and re.fullmatch(r"\d{3}\.\d{2}", w.text) and w.x0 - out[-1].x1 < 12:
            out[-1] = W(out[-1].text + w.text, out[-1].x0, w.x1, out[-1].top, out[-1].font)
            continue
        out.append(w)
    return out


# ---------------------------------------------------------------- header and columns

def _classify(text: str, exact: bool) -> str | None:
    t = text.lower().strip(" .:")
    for col, kws in COLUMN_WORDS.items():
        for k in kws:
            if t == k.strip(" .:"):
                return col
            if not exact and len(t) > 3 and t.startswith(k):
                return col
    return None


def find_header(line: Line) -> dict[str, tuple[float, float]] | None:
    """Returns {column: (x0, x1)} when the line looks like a transaction table header."""
    cols: dict[str, tuple[float, float]] = {}
    ws = line.words
    i = 0
    while i < len(ws):
        # try two- and three-word headings first ("Withdrawal Amt.", "Closing Balance", "Txn Date")
        hit = None
        for n in (3, 2, 1):
            if i + n <= len(ws):
                txt = " ".join(w.text for w in ws[i:i + n])
                col = _classify(txt, exact=n > 1)
                if col:
                    hit = (col, n)
                    break
        if hit:
            col, n = hit
            x0, x1 = ws[i].x0, ws[i + n - 1].x1
            if col == "date" and "date" in cols:
                col = "valuedate"
            if col not in cols:
                cols[col] = (x0, x1)
            i += n
        else:
            i += 1
    has_amounts = ("debit" in cols and "credit" in cols) or "amount" in cols
    if "date" in cols and "balance" in cols and has_amounts and len(cols) >= 4:
        return cols
    return None


# ---------------------------------------------------------------- table parsing

def parse_rows(lines: list[Line]) -> tuple[list[Row], dict]:
    rows: list[Row] = []
    info = {"headerFound": False, "headerPages": set()}
    cols = None
    in_table = False
    for line in lines:
        hdr = find_header(line)
        if hdr:
            cols, in_table = hdr, True
            info["headerFound"] = True
            info["headerPages"].add(line.page)
            continue
        if not line.words:
            continue
        low = line.text.lower()
        first = line.words[0].text
        d = parse_date(first)
        if d is None and len(line.words) > 1:
            d2 = parse_date(first + " " + line.words[1].text)  # '05 Aug 2026' split into words
            if d2:
                d = d2
                line = Line(line.page, line.top, [W(first + " " + line.words[1].text, line.words[0].x0, line.words[1].x1, line.top)] + line.words[2:])
            elif len(line.words) > 2:
                d3 = parse_date(first + " " + line.words[1].text + " " + line.words[2].text)
                if d3:
                    d = d3
                    line = Line(line.page, line.top, [W(" ".join(w.text for w in line.words[:3]), line.words[0].x0, line.words[2].x1, line.top)] + line.words[3:])
        if d:
            in_table = True
            rows.append(_row(line, d, cols))
            continue
        if not in_table or not rows:
            continue
        if any(low.startswith(f) for f in FOOTER_WORDS) or re.fullmatch(r"page \d+.*", low):
            in_table = False if not cols else in_table
            continue
        # continuation of the previous narration (a wrapped line without a date or amounts)
        if not any(parse_amount(w.text) for w in line.words):
            nx_max = cols["narration"][1] + 200 if cols and "narration" in cols else 10_000
            extra = " ".join(w.text for w in line.words if w.x0 < nx_max)
            if extra and len(rows[-1].narration) < 300:
                rows[-1].narration = (rows[-1].narration + " " + extra).strip()
    info["headerPages"] = sorted(info["headerPages"])
    return rows, info


def _row(line: Line, d: str, cols) -> Row:
    r = Row(date=d, page=line.page)
    words = line.words[1:]
    amounts = [(w, parse_amount(w.text)) for w in words if parse_amount(w.text)]
    texts = [w for w in words if not parse_amount(w.text) and not parse_date(w.text)]
    r.fonts = {w.font for w, _ in amounts if w.font}
    def is_marker(w: W) -> bool:
        if w.text.upper().strip(".") not in ("DR", "CR"):
            return False
        if cols and "drcr" in cols:
            x0, x1 = cols["drcr"]
            return x0 - 15 <= w.x0 <= x1 + 15
        # without a Dr/Cr column, only a marker written right after an amount counts
        return any(0 <= w.x0 - a.x1 < 20 for a, _ in amounts)
    markers = [w for w in texts if is_marker(w)]
    drcr_word = markers[0].text.upper() if markers else None
    texts = [w for w in texts if w not in markers]
    if cols:
        num_cols = {k: v for k, v in cols.items() if k in ("debit", "credit", "amount", "balance")}
        for w, (val, sign) in amounts:
            # nearest numeric column by right edge (amounts are right-aligned under their heading)
            best = min(num_cols, key=lambda k: min(abs(num_cols[k][1] - w.x1),
                                                    abs((num_cols[k][0] + num_cols[k][1]) / 2 - (w.x0 + w.x1) / 2)))
            if best == "balance":
                r.balance = -val if sign == "DR" else val
            elif best == "debit":
                r.debit = val
            elif best == "credit":
                r.credit = val
            else:  # single amount column; direction from Dr/Cr marker, else from the balance later
                kind = sign or drcr_word
                if kind and kind.startswith("DR"):
                    r.debit = val
                elif kind and kind.startswith("CR"):
                    r.credit = val
                else:
                    r.credit, r.direction_inferred = val, True
        ref_col = cols.get("ref")
        nar = []
        for w in texts:
            if ref_col and ref_col[0] - 5 <= w.x0 <= ref_col[1] + 40 and re.search(r"\d{5,}", w.text):
                r.ref = (r.ref + " " + w.text).strip()
            elif "valuedate" in cols and parse_date(w.text):
                continue
            else:
                nar.append(w.text)
        r.narration = " ".join(nar)
    else:
        # no header: last amount is the balance, the one before it the transaction amount
        if amounts:
            val, sign = amounts[-1][1]
            r.balance = -val if sign == "DR" else val
        if len(amounts) >= 2:
            r.credit, r.direction_inferred = amounts[-2][1][0], True
            if (amounts[-2][1][1] or drcr_word or "").startswith("DR"):
                r.debit, r.credit, r.direction_inferred = r.credit, 0.0, False
        r.narration = " ".join(w.text for w in texts)
    return r


def fix_directions(rows: list[Row], opening: float | None) -> None:
    """Where direction was a guess, take it from how the balance moved."""
    prev = opening
    for r in rows:
        if r.direction_inferred and prev is not None and r.balance is not None:
            amt = r.credit or r.debit
            if abs(prev - amt - r.balance) < abs(prev + amt - r.balance):
                r.debit, r.credit = amt, 0.0
            else:
                r.credit, r.debit = amt, 0.0
            r.direction_inferred = False
        if r.balance is not None:
            prev = r.balance


def reconcile(rows: list[Row], opening: float | None) -> dict:
    checked = passed = 0
    breaks = []
    prev = opening
    for i, r in enumerate(rows):
        if prev is not None and r.balance is not None:
            checked += 1
            expected = round(prev + r.credit - r.debit, 2)
            if abs(expected - r.balance) <= 1.0:
                passed += 1
            else:
                breaks.append({"row": i, "date": r.date, "narration": r.narration[:60], "expected": expected, "printed": r.balance})
        if r.balance is not None:
            prev = r.balance
    return {"checked": checked, "passed": passed, "rate": round(passed / checked, 4) if checked else 0.0, "breaks": breaks[:25]}


# ---------------------------------------------------------------- header fields

def header_fields(lines: list[Line]) -> dict:
    top = [l.text for l in lines if l.page == 0][:40]
    text = "\n".join(top)
    out: dict = {}

    def grab(patterns):
        for p in patterns:
            m = re.search(p, text, re.I)
            if m:
                return m.group(1).strip()
        return None

    out["accountHolder"] = grab([r"Account\s*Holder\s*(?:Name)?\s*[:\-]\s*([A-Z][A-Z .']{2,60}?)(?:\s{2,}|\s+IFSC|\s+Branch|\n|$)",
                                 r"(?:Customer|A/c)\s*Name\s*[:\-]\s*([A-Z][A-Z .']{2,60}?)(?:\s{2,}|\n|$)",
                                 r"^Name\s*[:\-]\s*([A-Z][A-Z .']{2,60}?)(?:\s{2,}|\n|$)"])
    acc = grab([r"Account\s*(?:No|Number)\.?\s*[:\-]?\s*([0-9X*]{6,20})", r"A/c\s*No\.?\s*[:\-]?\s*([0-9X*]{6,20})"])
    out["accountNumberMasked"] = ("XXXX" + re.sub(r"\D", "", acc)[-4:]) if acc else None
    out["ifsc"] = grab([r"IFSC\s*(?:Code)?\s*[:\-]?\s*([A-Z]{4}0[A-Z0-9]{6})"])
    period = re.search(r"(?:Period|From)\s*[:\-]?\s*(\S+)\s*(?:to|-)\s*(\S+)", text, re.I)
    if period:
        out["periodFrom"], out["periodTo"] = parse_date(period.group(1)), parse_date(period.group(2))
    ob = grab([r"Opening\s*Balance\s*[:\-]?\s*(?:Rs\.?|INR|₹)?\s*([\d,]+\.\d{2})"])
    out["openingBalance"] = float(ob.replace(",", "")) if ob else None
    bank = None
    for l in top[:6]:
        if re.search(r"\bbank\b", l, re.I):
            bank = re.sub(r"\s+IFSC.*$", "", l).strip()
            break
    out["bank"] = bank
    alltext = "\n".join(l.text for l in lines)
    cb = re.findall(r"Closing\s*Balance\s*[:\-]?\s*(?:Rs\.?|INR|₹)?\s*([\d,]+\.\d{2})", alltext, re.I)
    out["closingBalance"] = float(cb[-1].replace(",", "")) if cb else None
    return out
