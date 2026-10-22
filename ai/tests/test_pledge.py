from fastapi.testclient import TestClient

from app import pledge
from app.main import app
from tools.collateral_gen import pdf

client = TestClient(app)


def test_cas_holdings():
    r = pledge.analyze_document(pdf("CONSOLIDATED ACCOUNT STATEMENT", [
        "Investor: Ashok Naik", "Depository: CDSL", "BO ID: 1208160012345678",
        "INE0DM101015   Western Ghats Power Ltd (demo)   Qty: 400",
        "INE0DM201013   Konkan Pharma Ltd (demo)   Qty: 600",
        "INF0DM501018   Sahyadri Bluechip Equity Fund - Growth (demo)   Units: 3,250.500",
        "INE0DM401019   Satpuda Speculative Mining Ltd (demo)   Qty: 5000"]), "application/pdf", "CAS_STATEMENT")
    f = r["fields"]
    assert r["missing"] == [] and f["holder"]["value"] == "Ashok Naik" and f["depository"]["value"] == "CDSL" and f["dematId"]["value"] == "1208160012345678"
    h = f["holdings"]["value"]
    assert [x["isin"] for x in h] == ["INE0DM101015", "INE0DM201013", "INF0DM501018", "INE0DM401019"]
    assert h[0]["quantity"] == 400 and h[2]["quantity"] == 3250.5 and h[0]["name"] == "Western Ghats Power Ltd (demo)"


def test_fd_receipt_and_lien():
    r = pledge.analyze_document(pdf("FIXED DEPOSIT RECEIPT", ["Bank: Fictional Co-operative Bank Ltd", "FD No: FCB/FD/2026/88123", "Depositor: Kavita Rane",
                                                             "Principal: 5,00,000", "Rate of interest: 7.25%", "Date of deposit: 15-06-2026", "Maturity date: 15-06-2029"]),
                                "application/pdf", "FD_RECEIPT")
    f = r["fields"]
    assert r["missing"] == [] and f["principal"]["value"] == 500000 and f["rate"]["value"] == 7.25 and f["maturityDate"]["value"] == "2029-06-15"
    res = client.post("/v1/pledge/documents/analyze", files={"file": ("l.pdf", pdf("LIEN CONFIRMATION", ["Lien ref: FCB/LIEN/26/4411", "In favour of: Rhythm Finance Ltd"]), "application/pdf")},
                      data={"doc_type": "lien_confirmation"})
    assert res.json()["fields"]["lienRef"]["value"] == "FCB/LIEN/26/4411"
