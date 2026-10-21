from fastapi.testclient import TestClient

from app import trade
from app.main import app
from tools import trade_gen as gen

client = TestClient(app)


def test_invoice_both_gstins_due_date_po():
    out = trade.analyze_invoice(gen.invoice(), "application/pdf")
    f = out["fields"]
    assert f["invoiceNo"]["value"] == "DPC/2026/0815"
    assert f["date"]["value"] == "2026-10-18" and f["dueDate"]["value"] == "2026-12-17"
    assert f["sellerGstin"]["value"] == "27DESPD4512K1Z3" and f["buyerGstin"]["value"] == "27AAWCW7731B1Z8"
    assert f["poNo"]["value"] == "WAC-PO-44812"
    assert f["basePrice"]["value"] == 500000 and f["gst"]["value"] == 90000 and f["total"]["value"] == 590000
    assert out["missing"] == [] and out["readConfidence"] >= 0.85


def test_due_date_from_payment_terms():
    f = trade.analyze_invoice(gen.invoice(terms="60 days from invoice"), "application/pdf")["fields"]
    assert f["dueDate"]["value"] == "2026-12-17" and f["terms"]["value"] == 60


def test_total_mismatch_flagged_and_endpoint():
    assert "total" in trade.analyze_invoice(gen.invoice(total="6,10,000"), "application/pdf")["toConfirm"]
    res = client.post("/v1/trade/invoices/analyze", files={"file": ("i.pdf", gen.invoice(), "application/pdf")})
    assert res.status_code == 200 and res.json()["fields"]["total"]["value"] == 590000
