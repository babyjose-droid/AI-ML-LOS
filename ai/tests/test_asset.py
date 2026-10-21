from fastapi.testclient import TestClient

from app import asset
from app.main import app
from tools import asset_gen as gen

client = TestClient(app)


def test_invoice_fields_and_gst_sum():
    out = asset.analyze_document(gen.invoice(), "application/pdf", "INVOICE")
    f = out["fields"]
    assert f["invoiceNo"]["value"] == "SCM/INV/2026/0412"
    assert f["date"]["value"] == "2026-10-14"
    assert f["gstin"]["value"] == "27AAKCS4512M1Z5"
    assert f["serialNo"]["value"] == "MAT448123R1K22871"
    assert f["basePrice"]["value"] == 2240000 and f["gst"]["value"] == 627200 and f["otherCharges"]["value"] == 35000
    assert f["total"]["value"] == 2902200
    assert out["missing"] == [] and out["readConfidence"] >= 0.85


def test_total_that_does_not_add_up_is_flagged():
    out = asset.analyze_document(gen.invoice(total="31,00,000"), "application/pdf", "INVOICE")
    assert "total" in out["toConfirm"]


def test_quotation_receipt_insurance_rc():
    q = asset.analyze_document(gen.quotation(), "application/pdf", "QUOTATION")["fields"]
    assert q["quoteNo"]["value"] == "SCM/Q/2026/0388" and q["total"]["value"] == 2902200
    r = asset.analyze_document(gen.receipt(), "application/pdf", "DOWN_PAYMENT_RECEIPT")["fields"]
    assert r["amount"]["value"] == 440000 and r["receiptNo"]["value"] == "SCM/R/1187"
    i = asset.analyze_document(gen.insurance(), "application/pdf", "INSURANCE")["fields"]
    assert i["insuredValue"]["value"] == 2550000 and i["validTill"]["value"] == "2027-10-13" and i["hypothecated"]["value"] is True
    n = asset.analyze_document(gen.insurance(hyp="Nil"), "application/pdf", "INSURANCE")["fields"]
    assert n["hypothecated"]["value"] is False
    c = asset.analyze_document(gen.rc(), "application/pdf", "RC_HP")["fields"]
    assert c["registrationNo"]["value"] == "MH15KL4421" and c["hypothecated"]["value"] is True


def test_endpoint():
    res = client.post("/v1/assets/documents/analyze", files={"file": ("i.pdf", gen.receipt(), "application/pdf")},
                      data={"doc_type": "down_payment_receipt"})
    assert res.status_code == 200 and res.json()["fields"]["amount"]["value"] == 440000
