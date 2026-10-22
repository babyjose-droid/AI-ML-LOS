from fastapi.testclient import TestClient

from app import plan
from app.main import app
from tools.collateral_gen import pdf

client = TestClient(app)


def test_fee_letter_terms_and_admission():
    f = plan.analyze_document(pdf("FEE STRUCTURE", ["Institution: Kalaram Institute of Nursing Sciences", "Student: Aditi Patwardhan",
                                                    "Course: B.Sc Nursing", "Academic year: 2026-27", "Term 1 fee: 1,60,000", "Term 2 fee: 1,60,000",
                                                    "Total fees: 3,20,000"]), "application/pdf", "FEE_LETTER")
    assert f["missing"] == [] and f["fields"]["terms"]["value"] == [{"term": 1, "amount": 160000}, {"term": 2, "amount": 160000}]
    assert f["fields"]["total"]["value"] == 320000
    a = plan.analyze_document(pdf("ADMISSION LETTER", ["Student: Aditi Patwardhan", "Institution: Kalaram Institute of Nursing Sciences",
                                                       "Course: B.Sc Nursing", "Start date: 01-08-2026", "Duration: 4 years", "Admission No: KINS/26/0412"]),
                              "application/pdf", "ADMISSION_LETTER")
    assert a["fields"]["durationMonths"]["value"] == 48 and a["fields"]["startDate"]["value"] == "2026-08-01" and a["missing"] == []


def test_land_record_hectares_to_acres_and_stage_certificate():
    r = plan.analyze_document(pdf("7/12 EXTRACT (SATBARA)", ["Village: Pimpalgaon Baswant", "Gat No: 418/2", "Occupant: Vitthal Shelke",
                                                            "Area: 1.62 ha", "Crops: Onion, Soybean"]), "application/pdf", "LAND_RECORD_712")
    assert r["fields"]["areaAcres"]["value"] == 4.0 and r["fields"]["surveyNo"]["value"] == "418/2" and r["missing"] == []
    s = plan.analyze_document(pdf("STAGE CERTIFICATE", ["Engineer: Er. S Wagh", "Stage: Roof slab cast", "Percent complete: 55%"]), "application/pdf", "STAGE_CERTIFICATE")
    assert s["fields"]["percent"]["value"] == 55 and s["missing"] == []


def test_estimate_and_endpoint():
    e = pdf("CONSTRUCTION ESTIMATE", ["Engineer: Er. S Wagh", "Built-up area: 1,050 sq ft", "Carpet area: 860 sq ft", "Approved plan no: NMC/BP/2026/1187",
                                      "Total estimate: 18,00,000"])
    res = client.post("/v1/plan/documents/analyze", files={"file": ("e.pdf", e, "application/pdf")}, data={"doc_type": "construction_estimate"})
    f = res.json()["fields"]
    assert f["total"]["value"] == 1800000 and f["carpetAreaSqft"]["value"] == 860 and f["planNo"]["value"] == "NMC/BP/2026/1187"
