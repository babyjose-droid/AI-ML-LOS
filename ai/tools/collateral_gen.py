"""SPECIMEN collateral documents (text-layer PDFs) for tests and demos. Every page is stamped SPECIMEN."""
import io

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas


def pdf(title: str, lines: list[str]) -> bytes:
    buf = io.BytesIO()
    c = canvas.Canvas(buf, pagesize=A4)
    c.setFont("Helvetica-Bold", 14)
    c.drawString(50, 800, title)
    c.setFont("Helvetica", 10)
    c.drawString(450, 815, "SPECIMEN")
    y = 770
    for ln in lines:
        c.drawString(50, y, ln)
        y -= 16
    c.save()
    return buf.getvalue()


def sale_deed(owner="Ramesh Patil", seller="Anil Kulkarni", survey="CTS 1234/5", area="1,850 sq ft", regno="4521/2016", date="12-05-2016"):
    return pdf("SALE DEED", [f"Owner: {owner}", f"Seller: {seller}", f"Survey No: {survey}", f"Area: {area}",
                             f"Registration No: {regno}", f"Date of registration: {date}", "Sub-Registrar: Nashik 2"])


def ec(rows: list[str], frm="01-01-2012", to="30-09-2026", survey="CTS 1234/5"):
    return pdf("ENCUMBRANCE CERTIFICATE (FORM 15)", [f"Period from: {frm}", f"Period to: {to}", f"Survey No: {survey}", "Transactions:"] + rows)


def valuation(market="₹ 85,00,000", realisable="₹ 76,50,000", distress="₹ 63,75,000", valuer="Er. P Deshmukh (empanelled)", date="01-09-2026"):
    return pdf("VALUATION REPORT", [f"Market value: {market}", f"Realisable value: {realisable}", f"Distress value: {distress}",
                                    f"Valuer: {valuer}", f"Date of valuation: {date}", "Method: Sales comparison"])
