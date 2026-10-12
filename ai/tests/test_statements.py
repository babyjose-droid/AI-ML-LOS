import base64

import pytest

from app.statements_analyze import PasswordRequired, analyse_statement
from tools import statements_gen as g


@pytest.fixture(scope="module")
def salaried():
    return g.generate("Meena Pawar", "salaried", 6, "A", seed=11, bounce_months=(2,), gambling=True)


@pytest.mark.parametrize("layout", ["A", "B", "C"])
def test_every_layout_reads_and_reconciles(layout):
    st = g.generate("Asha Kulkarni", "salaried" if layout != "C" else "msme", 6, layout, seed=ord(layout))
    r = analyse_statement(g.render_pdf(st), "application/pdf")
    assert r["status"] == "OK", r["reviewReasons"]
    assert r["transactionCount"] == len(st.txns)
    assert r["reconciliation"]["rate"] == 1.0
    assert r["accountHolder"] == "ASHA KULKARNI"
    assert r["accountNumberMasked"] == "XXXX" + st.account[-4:]
    for got, exp in zip(r["transactions"], st.txns):
        assert (got["debit"], got["credit"], got["balance"]) == (exp.debit, exp.credit, exp.balance)
        assert got["category"] == exp.truth, (got["narration"], got["category"], exp.truth)


def test_cash_flow_summary(salaried):
    r = analyse_statement(g.render_pdf(salaried), "application/pdf")
    s = r["summary"]
    assert s["months"] == 6
    assert s["salary"]["amount"] == salaried.truth["salary"]
    assert s["detectedEmi"] == salaried.truth["emi"]
    assert s["bounces"] == 1 and s["bounceCharges"] == 590.0
    assert s["gamblingCount"] == salaried.truth["gambling"] > 0
    assert len(r["monthly"]) == 6
    assert all(m["avgBalance"] > 0 for m in r["monthly"])
    flagged = [t for t in r["transactions"] if t["flags"]]
    assert any("Betting" in f for t in flagged for f in t["flags"])


def test_edited_statement_is_caught(salaried):
    r = analyse_statement(g.render_pdf(g.edited(salaried)), "application/pdf")
    assert r["status"] == "NEEDS_REVIEW"
    assert r["tamper"]["score"] >= 0.5
    assert r["reconciliation"]["breaks"]


def test_editing_tool_metadata_is_flagged(salaried):
    r = analyse_statement(g.render_pdf(salaried, producer="Sejda PDF Editor"), "application/pdf")
    assert any("editing tool" in s for s in r["tamper"]["signals"])


def test_password_locked_statement(salaried):
    locked = g.locked(g.render_pdf(salaried), "MEEN1990")
    with pytest.raises(PasswordRequired):
        analyse_statement(locked, "application/pdf")
    with pytest.raises(PasswordRequired):
        analyse_statement(locked, "application/pdf", "wrong")
    r = analyse_statement(locked, "application/pdf", "MEEN1990")
    assert r["passwordProtected"] and r["unlockedPdf"]
    assert base64.b64decode(r["unlockedPdf"])[:5] == b"%PDF-"
    assert r["transactionCount"] == len(salaried.txns)


def test_aes256_locked_statement(salaried):
    # many banks encrypt statements with AES; this needs the cryptography package in the image
    r = analyse_statement(g.locked(g.render_pdf(salaried), "pw-9", "AES-256"), "application/pdf", "pw-9")
    assert r["transactionCount"] == len(salaried.txns) and r["status"] == "OK"


def test_scanned_statement_uses_ocr():
    st = g.generate("Meena Pawar", "salaried", 3, "B", seed=21)
    r = analyse_statement(g.scanned(g.render_pdf(st)), "application/pdf")
    assert r["engine"].startswith("ocr")
    assert r["transactionCount"] >= len(st.txns) - 2
    assert r["reconciliation"]["rate"] >= 0.9
    assert r["summary"]["salary"]["amount"] == st.truth["salary"]


def test_not_a_statement_is_unreadable():
    from tools import specimens as sp
    r = analyse_statement(sp.png(sp.pan_card("Asha Kulkarni", "R Kulkarni", "01/01/1990", "ASHPK1234A")), "image/png")
    assert r["status"] == "UNREADABLE"


def test_statement_api():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    st = g.generate("Meena Pawar", "salaried", 3, "C", seed=5)
    pdf = g.locked(g.render_pdf(st), "PW12")
    assert c.post("/v1/statements/analyze", files={"file": ("s.pdf", pdf, "application/pdf")}).status_code == 422
    r = c.post("/v1/statements/analyze", files={"file": ("s.pdf", pdf, "application/pdf")}, data={"password": "PW12"})
    assert r.status_code == 200 and r.json()["transactionCount"] == len(st.txns)
    u = c.post("/v1/pdf/unlock", files={"file": ("s.pdf", pdf, "application/pdf")}, data={"password": "PW12"})
    assert u.status_code == 200 and u.json()["wasEncrypted"]
    assert c.post("/v1/pdf/unlock", files={"file": ("s.pdf", pdf, "application/pdf")}, data={"password": "x"}).status_code == 422
