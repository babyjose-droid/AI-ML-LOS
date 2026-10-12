"""Orchestrates one bank statement read: unlock -> words (text layer or OCR) -> table -> reconcile -> cash flow -> signals."""
import base64
import io
import json
import logging
import os
import re

from . import llm
from .cashflow import analyse
from .statement import (Row, fix_directions, header_fields, parse_rows, reconcile, words_from_images, words_from_pdf)
from .tamper import EDITORS

log = logging.getLogger("rhythm.ai.statement")
MAX_PAGES = int(os.getenv("STATEMENT_MAX_PAGES", "40"))


class PasswordRequired(Exception):
    pass


def unlock(data: bytes, password: str | None) -> tuple[bytes, bool]:
    """Returns (pdf bytes without encryption, was_encrypted). Raises PasswordRequired."""
    from pypdf import PdfReader, PdfWriter
    r = PdfReader(io.BytesIO(data))
    if not r.is_encrypted:
        return data, False
    if not password or r.decrypt(password) == 0:
        raise PasswordRequired()
    w = PdfWriter()
    for p in r.pages:
        w.add_page(p)
    if r.metadata:
        w.add_metadata({k: v for k, v in r.metadata.items() if isinstance(v, str)})
    b = io.BytesIO()
    w.write(b)
    return b.getvalue(), True


def _rows_to_txns(rows: list[Row]) -> list[dict]:
    return [{"date": r.date, "narration": r.narration, "ref": r.ref, "debit": round(r.debit, 2), "credit": round(r.credit, 2),
             "balance": None if r.balance is None else round(r.balance, 2)} for r in rows]


def _render(data: bytes):
    import pypdfium2 as pdfium
    pdf = pdfium.PdfDocument(data)
    return [pdf[i].render(scale=2.5).to_pil().convert("RGB") for i in range(min(len(pdf), MAX_PAGES))]


def _claude_rows(images) -> list[Row] | None:
    """Opt-in fallback for scans OCR could not reconcile (STATEMENT_LLM_FALLBACK=true and ANTHROPIC_API_KEY)."""
    if not (llm.enabled() and os.getenv("STATEMENT_LLM_FALLBACK", "false").lower() == "true"):
        return None
    rows: list[Row] = []
    for img in images[:6]:
        b = io.BytesIO()
        img.save(b, "PNG")
        res = llm.read_statement_page(b.getvalue())
        if res is None:
            return None
        for t in res:
            try:
                rows.append(Row(date=t["date"], narration=str(t.get("narration", ""))[:200], debit=float(t.get("debit") or 0),
                                credit=float(t.get("credit") or 0), balance=float(t["balance"]) if t.get("balance") is not None else None))
            except (KeyError, ValueError, TypeError):
                continue
    return rows or None


def analyse_statement(data: bytes, content_type: str, password: str | None = None, holder_hint: str | None = None,
                      declared_income: float | None = None) -> dict:
    meta, engine, unlocked, was_locked = {}, "pdf-text", None, False
    is_pdf = content_type == "application/pdf" or data[:5] == b"%PDF-"
    images = None
    if is_pdf:
        data, was_locked = unlock(data, password)
        if was_locked:
            unlocked = data
        lines, meta, has_text = words_from_pdf(data, None)
        rows, info = parse_rows(lines) if has_text else ([], {"headerFound": False})
        if len(rows) < 3:
            images = _render(data)
            lines = words_from_images(images)
            rows, info = parse_rows(lines)
            engine = "ocr"
    else:
        from PIL import Image, ImageOps
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
        images = [img]
        lines = words_from_images(images)
        rows, info = parse_rows(lines)
        engine = "ocr"

    head = header_fields(lines)
    opening = head.get("openingBalance")
    fix_directions(rows, opening)
    rec = reconcile(rows, opening)
    if engine == "ocr" and rec["rate"] < 0.9 and images:
        alt = _claude_rows(images)
        if alt:
            fix_directions(alt, opening)
            alt_rec = reconcile(alt, opening)
            if alt_rec["rate"] > rec["rate"]:
                rows, rec, engine = alt, alt_rec, "ocr+claude"

    txns = _rows_to_txns(rows)
    holder = head.get("accountHolder") or holder_hint
    cash = analyse(txns, holder, opening, declared_income) if txns else {"monthly": [], "summary": {}}

    # ---- signals
    signals, notes = [], []
    n_breaks = rec["checked"] - rec["passed"]
    if n_breaks and (engine == "pdf-text" or rec["rate"] < 0.98):
        n = n_breaks
        if engine == "pdf-text":
            signals.append((f"Balances do not add up on {n} of {rec['checked']} rows (text-layer PDF: possible editing)", 0.6))
        else:
            notes.append(f"Balances do not add up on {n} of {rec['checked']} rows (likely OCR reading errors)")
    prod = (meta.get("Producer", "") + " " + meta.get("Creator", "")).lower()
    if any(e in prod for e in EDITORS) or "pdf editor" in prod:
        signals.append((f"PDF was produced by an editing tool ({prod.strip()[:60]})", 0.5))
    cd, md = meta.get("CreationDate"), meta.get("ModDate")
    if cd and md and cd[:16] != md[:16]:
        signals.append(("PDF was modified after it was created", 0.25))
    fonts = set()
    for r in rows:
        fonts |= r.fonts
    if engine == "pdf-text" and len(fonts) > 2:
        signals.append((f"Amounts are printed in {len(fonts)} different fonts", 0.4))
    s = cash.get("summary", {})
    if head.get("closingBalance") is not None and txns and txns[-1]["balance"] is not None \
            and abs(head["closingBalance"] - txns[-1]["balance"]) > 1:
        signals.append(("Closing balance in the summary differs from the last transaction", 0.5))
    if s.get("duplicates"):
        notes.append(f"{s['duplicates']} duplicate transaction(s)")
    if s.get("missingMonths"):
        notes.append("No transactions in " + ", ".join(s["missingMonths"]))
    if s.get("daysSinceLastTransaction") is not None and s["daysSinceLastTransaction"] > 45:
        notes.append(f"Statement ends {s['daysSinceLastTransaction']} days ago")
    if s.get("cashDepositShare", 0) > 0.5 and s.get("roundAmountShare", 0) > 0.6:
        notes.append("Most income is round-figure cash deposits")
    tamper_score = min(1.0, sum(w for _, w in signals))

    if not info.get("headerFound") and (len(txns) < 3 or not rec["checked"]):
        # No table header and nothing that reconciles: this is not a bank statement.
        txns, rec = [], {"checked": 0, "passed": 0, "rate": 0.0, "breaks": []}
        cash, s, notes, signals, tamper_score = {"monthly": [], "summary": {}}, {}, [], [], 0.0
    reasons = []
    if not txns:
        reasons.append("No transactions could be read")
    elif rec["checked"] and rec["rate"] < 0.95:
        reasons.append(f"Only {round(rec['rate'] * 100)}% of balances reconcile")
    elif not rec["checked"]:
        reasons.append("Balances could not be read, so nothing could be reconciled")
    if not info.get("headerFound") and txns:
        reasons.append("Transaction table header not found; columns were inferred")
    if tamper_score >= 0.5:
        reasons.append("Possible editing: " + "; ".join(x for x, _ in signals))
    if txns and not holder:
        reasons.append("Account holder name not found")
    status = "UNREADABLE" if not txns else ("NEEDS_REVIEW" if reasons else "OK")

    return {
        "status": status,
        "documentType": "BANK_STATEMENT",
        "engine": engine,
        "passwordProtected": was_locked,
        "unlockedPdf": base64.b64encode(unlocked).decode() if unlocked else None,
        "bank": head.get("bank"),
        "accountHolder": head.get("accountHolder"),
        "accountNumberMasked": head.get("accountNumberMasked"),
        "ifsc": head.get("ifsc"),
        "periodFrom": head.get("periodFrom") or (txns[0]["date"] if txns else None),
        "periodTo": head.get("periodTo") or (txns[-1]["date"] if txns else None),
        "openingBalance": opening,
        "closingBalance": head.get("closingBalance"),
        "transactions": txns[:3000],
        "transactionCount": len(txns),
        "monthly": cash["monthly"],
        "summary": s,
        "reconciliation": rec,
        "tamper": {"score": round(tamper_score, 2), "signals": [x for x, _ in signals]},
        "notes": notes,
        "reviewReasons": reasons,
        "overallConfidence": round(rec["rate"] if rec["checked"] else (0.5 if txns else 0.0), 3),
        "headerFound": bool(info.get("headerFound")),
    }
