"""
SPECIMEN bank statements for tests, demos and accuracy evaluation.

The banks are fictional. Three layouts mimic the common table styles seen in Indian statements:
  A  Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance   (dd/mm/yy)
  B  Txn Date | Description (wraps) | Debit | Credit | Balance                                    (dd-Mon-yyyy)
  C  Date | Particulars | Amount | Dr/Cr | Balance (with Cr/Dr suffix)                              (dd.mm.yyyy)

Variants: scanned (image-only PDF), edited (one deposit amount changed so balances no longer add up,
saved by an editing tool), and password-locked.
"""
import io
import random
from dataclasses import dataclass, field
from datetime import date, timedelta

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

BANKS = {
    "A": ("SAHYADRI CO-OPERATIVE BANK LTD", "SAHY0001234"),
    "B": ("Coastal Commercial Bank", "CCBL0000456"),
    "C": ("NORTHERN PEOPLES BANK", "NPBK0007890"),
}


@dataclass
class Txn:
    date: date
    narration: str
    debit: float = 0.0
    credit: float = 0.0
    balance: float = 0.0
    ref: str = ""
    truth: str = ""  # expected category


@dataclass
class Statement:
    layout: str
    holder: str
    account: str
    opening: float
    start: date
    end: date
    txns: list[Txn] = field(default_factory=list)
    truth: dict = field(default_factory=dict)

    @property
    def bank(self):
        return BANKS[self.layout][0]

    @property
    def ifsc(self):
        return BANKS[self.layout][1]


def _month_starts(start: date, months: int):
    y, m = start.year, start.month
    for _ in range(months):
        yield date(y, m, 1)
        m += 1
        if m == 13:
            y, m = y + 1, 1


def generate(holder: str, profile: str = "salaried", months: int = 6, layout: str = "A", seed: int = 1,
             bounce_months: tuple[int, ...] = (), gambling: bool = False, end: date | None = None,
             salary: int | None = None, employer: str | None = None) -> Statement:
    """A plausible statement for a fictional person. The ground truth is kept in Statement.truth."""
    rng = random.Random(seed)
    end = end or (date.today().replace(day=1) - timedelta(days=1))
    first = date(end.year, end.month, 1)
    for _ in range(months - 1):
        first = (first - timedelta(days=1)).replace(day=1)
    opening = float(rng.randint(8, 40) * 1000 + rng.randint(0, 99))
    txns: list[Txn] = []
    salary = (rng.randint(35, 95) * 1000 if profile == "salaried" else 0) if salary is None else salary
    emi = rng.choice([4500, 6800, 9200, 12500])
    employer = rng.choice(["ACME INDUSTRIES LTD", "HARBOUR SOFTWARE PVT LTD", "WESTERN TOOLS LTD"]) if employer is None else employer
    lender = rng.choice(["SWIFT FINANCE LTD", "TRUSTLINE CAPITAL", "METRO HOUSING FIN"])
    shops = ["KIRANA MART", "FRESH VEG", "CITY MEDICALS", "FUEL POINT", "BOOK STALL", "TEA HOUSE"]
    customers = ["RAJ TRADERS", "MAHESH STORES", "SHREE AGENCIES", "NEW INDIA PROVISIONS", "OM ENTERPRISES"]
    for mi, ms in enumerate(_month_starts(first, months)):
        def d(day):
            day = min(day, 28)
            return ms.replace(day=day)
        if profile == "salaried":
            txns.append(Txn(d(1), f"NEFT CR-{employer}-SALARY {ms.strftime('%b').upper()}", credit=salary, ref=f"N{rng.randint(10**9, 10**10 - 1)}", truth="SALARY"))
        else:
            for _ in range(rng.randint(10, 16)):
                amt = float(rng.randint(8, 60) * 100)
                txns.append(Txn(d(rng.randint(1, 28)), f"UPI/{rng.randint(10**11, 10**12 - 1)}/{rng.choice(customers)}/PAYMENT", credit=amt, truth="UPI"))
            for _ in range(rng.randint(2, 4)):
                txns.append(Txn(d(rng.randint(2, 27)), "BY CASH DEPOSIT CDM", credit=float(rng.randint(5, 30) * 1000), truth="CASH_DEPOSIT"))
            txns.append(Txn(d(rng.randint(5, 25)), f"NEFT DR-{rng.choice(['WHOLESALE FOODS', 'METRO DISTRIBUTORS'])}-SUPPLIER", debit=float(rng.randint(20, 60) * 1000), ref=f"N{rng.randint(10**9, 10**10 - 1)}", truth="TRANSFER_OUT"))
            txns.append(Txn(d(20), "GST PAYMENT CBIC CPIN", debit=float(rng.randint(2, 8) * 1000), truth="TAX"))
        bounced = mi in bounce_months
        txns.append(Txn(d(5), f"NACH DR {lender} EMI", debit=float(emi), ref=f"ACH{rng.randint(10**6, 10**7 - 1)}", truth="EMI"))
        if bounced:
            txns.append(Txn(d(5), f"NACH RTN {lender} INSUFFICIENT FUNDS", credit=float(emi), truth="BOUNCE"))
            txns.append(Txn(d(6), "ECS RETURN CHARGES INCL GST", debit=590.0, truth="BOUNCE_CHARGE"))
        for _ in range(rng.randint(6, 12)):
            txns.append(Txn(d(rng.randint(2, 28)), f"UPI/{rng.randint(10**11, 10**12 - 1)}/{rng.choice(shops)}/PAY", debit=float(rng.randint(1, 35) * 50 + rng.randint(0, 49)), truth="UPI"))
        txns.append(Txn(d(rng.randint(8, 25)), f"ATM WDL {rng.choice(['MG ROAD', 'STATION RD', 'MARKET YARD'])}", debit=float(rng.choice([2000, 3000, 5000, 10000])), truth="CASH_WITHDRAWAL"))
        if profile == "salaried":
            txns.append(Txn(d(3), "IMPS-RENT-LANDLORD", debit=float(rng.randint(8, 18) * 1000), truth="RENT"))
            txns.append(Txn(d(12), f"IMPS-SELF-{holder.split()[0].upper()} OTHER BANK", debit=float(rng.randint(2, 6) * 1000), truth="SELF_TRANSFER"))
        txns.append(Txn(d(15), "ELECTRICITY BILL BBPS", debit=float(rng.randint(6, 30) * 100), truth="UTILITIES"))
        if gambling and mi % 2 == 0:
            txns.append(Txn(d(18), "UPI/ONLINE RUMMY GAMES/ADD CASH", debit=float(rng.randint(5, 20) * 100), truth="GAMBLING"))
        if mi % 3 == 2:
            txns.append(Txn(d(28), "INT.PD:SB INTEREST", credit=float(rng.randint(40, 400)), truth="INTEREST"))
    txns.sort(key=lambda t: (t.date, 0 if t.credit else 1))
    out, bal = [], opening
    for t in txns:
        if bal + t.credit - t.debit < 500:  # keep the account plausible: a cash top-up before it would go negative
            top = float(rng.randint(10, 25) * 1000)
            while bal + top + t.credit - t.debit < 500:
                top += 10000.0
            bal = round(bal + top, 2)
            out.append(Txn(t.date, "BY CASH DEPOSIT CDM", credit=top, balance=bal, truth="CASH_DEPOSIT"))
        bal = round(bal + t.credit - t.debit, 2)
        t.balance = bal
        out.append(t)
    txns = out
    s = Statement(layout, holder, "".join(str(rng.randint(0, 9)) for _ in range(12)), opening, first, end, txns)
    credits = [t for t in txns if t.credit and t.truth not in ("BOUNCE",)]
    s.truth = {
        "count": len(txns), "salary": salary, "emi": emi, "bounces": len(bounce_months), "gambling": sum(1 for t in txns if t.truth == "GAMBLING"),
        "closing": txns[-1].balance, "months": months, "credits": round(sum(t.credit for t in credits), 2),
    }
    return s


# ---------------------------------------------------------------- rendering

def _amt(x: float) -> str:
    return f"{x:,.2f}" if x else ""


def _wrap(text: str, width: int) -> list[str]:
    out, cur = [], ""
    for w in text.split(" "):
        if len(cur) + len(w) + 1 > width and cur:
            out.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur:
        out.append(cur)
    return out


def render_pdf(s: Statement, producer: str | None = None, font: str = "Helvetica") -> bytes:
    b = io.BytesIO()
    c = canvas.Canvas(b, pagesize=A4)
    if producer:
        c.setProducer(producer)
    c.setTitle(f"Statement of account {s.account[-4:]}")
    W, H = A4
    page = 1

    def header():
        y = H - 40
        c.setFont(font + "-Bold", 13)
        c.drawString(36, y, s.bank)
        c.setFont(font, 9)
        c.drawString(36, y - 14, "Statement of Account  (SPECIMEN - fictional bank)")
        c.drawString(36, y - 32, f"Account Holder: {s.holder.upper()}")
        c.drawString(36, y - 44, f"Account No: {s.account}")
        c.drawString(320, y - 32, f"IFSC: {s.ifsc}")
        c.drawString(320, y - 44, f"Statement Period: {s.start.strftime('%d/%m/%Y')} to {s.end.strftime('%d/%m/%Y')}")
        if page == 1:
            c.drawString(36, y - 58, f"Opening Balance: {_amt(s.opening)}")
        return y - 80

    def cols():
        if s.layout == "A":
            return [("Date", 36, "l"), ("Narration", 86, "l"), ("Chq./Ref.No.", 255, "l"), ("Value Dt", 322, "l"),
                    ("Withdrawal Amt.", 436, "r"), ("Deposit Amt.", 498, "r"), ("Closing Balance", 566, "r")]
        if s.layout == "B":
            return [("Txn Date", 36, "l"), ("Description", 100, "l"), ("Debit", 420, "r"), ("Credit", 490, "r"), ("Balance", 566, "r")]
        return [("Date", 36, "l"), ("Particulars", 96, "l"), ("Amount", 420, "r"), ("Dr/Cr", 440, "l"), ("Balance", 566, "r")]

    def draw_header_row(y):
        c.setFont(font + "-Bold", 8)
        for name, x, al in cols():
            (c.drawRightString if al == "r" else c.drawString)(x, y, name)
        c.line(36, y - 4, W - 28, y - 4)
        return y - 16

    y = draw_header_row(header())
    c.setFont(font, 8)
    for t in s.txns:
        if s.layout == "A":
            narr = _wrap(t.narration, 34)
            cells = [(t.date.strftime("%d/%m/%y"), 36, "l"), (narr[0], 86, "l"), (t.ref[:12], 255, "l"),
                     (t.date.strftime("%d/%m/%y"), 322, "l"), (_amt(t.debit), 436, "r"), (_amt(t.credit), 498, "r"), (_amt(t.balance), 566, "r")]
        elif s.layout == "B":
            narr = _wrap(t.narration, 52)
            cells = [(t.date.strftime("%d-%b-%Y"), 36, "l"), (narr[0], 100, "l"), (_amt(t.debit), 420, "r"),
                     (_amt(t.credit), 490, "r"), (_amt(t.balance), 566, "r")]
        else:
            narr = _wrap(t.narration, 50)
            cells = [(t.date.strftime("%d.%m.%Y"), 36, "l"), (narr[0], 96, "l"), (_amt(t.debit or t.credit), 420, "r"),
                     ("Dr" if t.debit else "Cr", 440, "l"), (f"{t.balance:,.2f} {'Cr' if t.balance >= 0 else 'Dr'}", 566, "r")]
        needed = 12 * len(narr) + 4
        if y - needed < 50:
            c.setFont(font, 7)
            c.drawString(36, 30, f"Page {page}")
            c.showPage()
            page += 1
            y = draw_header_row(header())
            c.setFont(font, 8)
        for text, x, al in cells:
            (c.drawRightString if al == "r" else c.drawString)(x, y, text)
        nx = 86 if s.layout == "A" else 100 if s.layout == "B" else 96
        for extra in narr[1:]:
            y -= 10
            c.drawString(nx, y, extra)
        y -= 13
    c.setFont(font + "-Bold", 8)
    c.drawString(36, y - 8, f"Closing Balance: {_amt(s.txns[-1].balance)}")
    c.setFont(font, 7)
    c.drawString(36, 30, f"Page {page}  -  End of statement")
    c.save()
    return b.getvalue()


def edited(s: Statement, seed: int = 3) -> Statement:
    """Inflate one deposit without fixing the balances: what a quick edit in a PDF editor looks like."""
    import copy
    e = copy.deepcopy(s)
    rng = random.Random(seed)
    credits = [t for t in e.txns if t.credit > 1000]
    target = rng.choice(credits)
    target.credit = round(target.credit * 3, 2)
    e.truth["editedRow"] = e.txns.index(target)
    return e


def locked(pdf: bytes, password: str, algorithm: str = "RC4-128") -> bytes:
    from pypdf import PdfReader, PdfWriter
    r = PdfReader(io.BytesIO(pdf))
    w = PdfWriter()
    for p in r.pages:
        w.add_page(p)
    w.encrypt(password, algorithm=algorithm)
    b = io.BytesIO()
    w.write(b)
    return b.getvalue()


def scanned(pdf: bytes, seed: int = 5, scale: float = 2.2) -> bytes:
    """Image-only PDF, slightly tilted and noisy, as from a phone scan app."""
    import numpy as np
    import pypdfium2 as pdfium
    from PIL import Image
    rng = np.random.default_rng(seed)
    pages = []
    for p in pdfium.PdfDocument(pdf):
        img = p.render(scale=scale).to_pil().convert("L")
        a = np.array(img).astype(float) + rng.normal(0, 8, (img.height, img.width))
        img = Image.fromarray(np.clip(a, 0, 255).astype("uint8")).rotate(0.6, fillcolor=255, expand=False)
        pages.append(img.convert("RGB"))
    b = io.BytesIO()
    pages[0].save(b, "PDF", save_all=True, append_images=pages[1:], resolution=200)
    return b.getvalue()


if __name__ == "__main__":
    import sys
    from pathlib import Path
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "statements")
    out.mkdir(parents=True, exist_ok=True)
    for lay in "ABC":
        st = generate("Kavya Iyer", "salaried" if lay != "C" else "msme", 6, lay, seed=ord(lay), bounce_months=(4,) if lay == "B" else ())
        (out / f"statement-{lay}.pdf").write_bytes(render_pdf(st))
    print("written to", out)
