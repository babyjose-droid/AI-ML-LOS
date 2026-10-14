import io

from fastapi.testclient import TestClient
from PIL import Image

from app import collateral
from app.main import app
from tools import collateral_gen as gen

client = TestClient(app)


def test_sale_deed_fields():
    out = collateral.analyze_document(gen.sale_deed(), "application/pdf", "SALE_DEED")
    f = out["fields"]
    assert f["owner"]["value"] == "Ramesh Patil"
    assert f["surveyNo"]["value"] == "CTS1234/5"
    assert f["areaSqft"]["value"] == 1850
    assert f["registrationNo"]["value"] == "4521/2016"
    assert f["registrationDate"]["value"] == "2016-05-12"
    assert out["missing"] == [] and out["readConfidence"] >= 0.85


def test_ec_transactions_and_release_link():
    rows = ["12-05-2016 | Sale Deed | 4521/2016 | Anil Kulkarni -> Ramesh Patil",
            "20-06-2016 | Mortgage (deposit of title deeds) | 4890/2016 | Ramesh Patil -> State Bank of India",
            "15-03-2021 | Release of 4890/2016 | 2210/2021 | State Bank of India -> Ramesh Patil"]
    out = collateral.analyze_document(gen.ec(rows), "application/pdf", "EC")
    t = out["transactions"]
    assert [r["type"] for r in t] == ["SALE", "MORTGAGE", "RELEASE"]
    assert t[0]["to"] == "Ramesh Patil" and t[0]["from"] == "Anil Kulkarni"
    assert t[2]["linkedRef"] == "4890/2016"
    assert out["fields"]["periodFrom"]["value"] == "2012-01-01"


def test_valuation_amounts_in_indian_format():
    f = collateral.analyze_document(gen.valuation(), "application/pdf", "VALUATION_REPORT")["fields"]
    assert f["marketValue"]["value"] == 8_500_000
    assert f["distressValue"]["value"] == 6_375_000
    assert f["valuationDate"]["value"] == "2026-09-01"
    assert collateral.parse_amount("Rs. 46.75 lakh") == 4_675_000
    assert collateral.parse_amount("1.2 crore") == 12_000_000


def test_media_quality_and_endpoint():
    buf = io.BytesIO()
    Image.new("RGB", (800, 600), (20, 20, 20)).save(buf, "JPEG")
    out = collateral.analyze_media(buf.getvalue())
    assert out["quality"] in ("DARK", "BLURRY")
    assert "No GPS in the photo" in out["notes"]
    r = client.post("/v1/collateral/documents/analyze", files={"file": ("deed.pdf", gen.sale_deed(), "application/pdf")}, data={"doc_type": "SALE_DEED"})
    assert r.status_code == 200 and r.json()["fields"]["owner"]["value"] == "Ramesh Patil"
    r = client.post("/v1/collateral/media/analyze", files={"file": ("p.jpg", buf.getvalue(), "image/jpeg")})
    assert r.status_code == 200 and "quality" in r.json()
