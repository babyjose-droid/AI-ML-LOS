import base64
import io
import re

import pytest
from PIL import Image

from app.analyze import analyze
from app.validators import aadhaar_valid, mrz_check, pan_valid, verhoeff_digit
from tools import specimens as sp


def run(img, declared=None, fmt="png", software=None):
    data = sp.png(img) if fmt == "png" else sp.jpeg(img, software)
    return analyze(data, "image/png" if fmt == "png" else "image/jpeg", declared)


def val(r, k):
    return r["fields"].get(k, {}).get("value")


# ---------- validators ----------

def test_verhoeff_and_aadhaar():
    n = "23456789012" + verhoeff_digit("23456789012")
    assert aadhaar_valid(n)
    assert not aadhaar_valid(n[:-1] + str((int(n[-1]) + 1) % 10))
    assert not aadhaar_valid("1" + n[1:])  # Aadhaar never starts with 0 or 1


def test_pan_format():
    assert pan_valid("ABCPK1234L")
    assert not pan_valid("ABCXK1234L")
    assert not pan_valid("ABCPK12345")


def test_mrz_check_digit():
    assert mrz_check("L898902C3", "6")  # ICAO 9303 sample


# ---------- reading specimen documents ----------

def test_pan_card():
    r = run(sp.pan_card("Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R"), "PAN")
    assert r["documentType"] == "PAN"
    assert val(r, "pan") == "RAMPP1001R"
    assert val(r, "name") == "RAMESH PATIL"
    assert val(r, "dob") == "1985-06-15"
    assert r["status"] == "OK", r["reviewReasons"]


def test_aadhaar_is_masked_everywhere():
    num = sp.aadhaar_number("PRIYA")
    r = run(sp.aadhaar_card("Priya Nair", "02/03/1992", "Female", num, ["4 Lake View", "Kochi, Kerala 682020"]), "AADHAAR")
    assert r["documentType"] == "AADHAAR"
    assert val(r, "aadhaarLast4") == num[-4:]
    assert val(r, "aadhaarMasked") == "XXXX XXXX " + num[-4:]
    assert val(r, "pincode") == "682020"
    assert r["masked"] is True
    # the full number must not appear anywhere in the response
    flat = str({k: v for k, v in r.items() if k != "maskedImage"})
    assert num not in flat.replace(" ", "")
    assert num[:4] + " " + num[4:8] not in flat
    # and the masked image must no longer show the first 8 digits
    img = Image.open(io.BytesIO(base64.b64decode(r["maskedImage"])))
    r2 = analyze(sp.png(img), "image/png", "AADHAAR")
    assert num[:8] not in re.sub(r"\D", "", r2["text"])


def test_invalid_aadhaar_checksum_goes_to_review():
    num = sp.aadhaar_number("ANIL")
    bad = num[:-1] + str((int(num[-1]) + 3) % 10)
    r = run(sp.aadhaar_card("Anil Kumar", "01/01/1995", "Male", bad), "AADHAAR")
    assert r["status"] != "OK"
    assert any("checksum" in x.lower() or "could not read" in x.lower() for x in r["reviewReasons"])


def test_passport_mrz():
    r = run(sp.passport("NAIR", "PRIYA", "920302", "320915", "Z1234567"), "PASSPORT")
    assert r["documentType"] == "PASSPORT"
    assert val(r, "passportNumber") == "Z1234567"
    assert val(r, "dob") == "1992-03-02"
    assert "fail" not in val(r, "mrzChecks")


def test_voter_dl_udyam_salary():
    r = run(sp.voter_card("Priya Nair", "Gopalan Nair", "02/03/1992", "KLX1234567"))
    assert r["documentType"] == "VOTER_ID" and val(r, "epicNumber") == "KLX1234567"
    r = run(sp.driving_licence("Ramesh Patil", "15/06/1985", "MH15 20050012345", "12 Main Road, Nashik"))
    assert r["documentType"] == "DRIVING_LICENCE" and val(r, "dlNumber") == "MH1520050012345"
    r = run(sp.udyam_certificate("Ramesh Patil", "PATIL KIRANA STORES", "UDYAM-MH-20-0012345"))
    assert r["documentType"] == "UDYAM" and val(r, "udyamNumber") == "UDYAM-MH-20-0012345"
    r = run(sp.salary_slip("Priya Nair", "Coastal Systems Pvt Ltd", "August 2026", 92000, 72000))
    assert r["documentType"] == "SALARY_SLIP" and val(r, "netPay") == "72000"


def test_wrong_document_type_is_flagged():
    r = run(sp.pan_card("Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R"), "AADHAAR")
    assert r["status"] == "NEEDS_REVIEW"
    assert any("looks like PAN" in x for x in r["reviewReasons"])


def test_blurred_document_is_not_accepted():
    r = run(sp.blurred(sp.pan_card("Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R"), 8), "PAN")
    assert r["status"] in ("UNREADABLE", "NEEDS_REVIEW")
    assert "Image is blurred" in r["quality"]["issues"]


def test_edited_image_is_flagged():
    r = run(sp.pan_card("Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R"), "PAN", fmt="jpeg", software="Adobe Photoshop 25.0")
    assert r["tamper"]["score"] >= 0.5
    assert r["status"] == "NEEDS_REVIEW"


def test_specimen_rejected_when_not_allowed(monkeypatch):
    monkeypatch.setenv("ALLOW_SPECIMEN", "false")
    r = run(sp.pan_card("Ramesh Patil", "Vitthal Patil", "15/06/1985", "RAMPP1001R"), "PAN")
    assert any("SPECIMEN" in x for x in r["reviewReasons"])


def test_pdf_input():
    img = sp.salary_slip("Deepa Menon", "Western Tools Ltd", "August 2026", 98000, 80000)
    b = io.BytesIO()
    img.save(b, "PDF", resolution=150)
    r = analyze(b.getvalue(), "application/pdf", "SALARY_SLIP")
    assert r["documentType"] == "SALARY_SLIP"
    assert val(r, "netPay") == "80000"


def test_api_endpoint():
    from fastapi.testclient import TestClient
    from app.main import app
    c = TestClient(app)
    assert c.get("/health").json()["status"] == "UP"
    data = sp.png(sp.pan_card("Deepa Menon", "Ravi Menon", "15/04/1990", "DEEPM4321D"))
    r = c.post("/v1/documents/analyze", files={"file": ("pan.png", data, "image/png")}, data={"declared_type": "PAN"})
    assert r.status_code == 200 and r.json()["fields"]["pan"]["value"] == "DEEPM4321D"
    assert c.post("/v1/documents/analyze", files={"file": ("x.txt", b"hi", "text/plain")}).status_code == 415
