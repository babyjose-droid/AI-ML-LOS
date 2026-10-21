"""SPECIMEN trade invoices (text-layer PDFs) for tests. Fictional sellers and buyers."""
from tools.collateral_gen import pdf


def invoice(no="DPC/2026/0815", date="18-10-2026", due="17-12-2026", terms=None, po="WAC-PO-44812",
            seller="Deshpande Precision Castings", seller_gstin="27DESPD4512K1Z3", buyer="Western Auto Components Ltd",
            buyer_gstin="27AAWCW7731B1Z8", taxable="5,00,000", cgst="45,000", sgst="45,000", total="5,90,000"):
    lines = [f"Supplier: {seller}", f"Supplier GSTIN: {seller_gstin}", f"Buyer: {buyer}", f"Buyer GSTIN: {buyer_gstin}",
             f"Invoice No: {no}", f"Invoice date: {date}"]
    lines += [f"Payment terms: {terms}"] if terms else [f"Due date: {due}"]
    lines += [f"PO No: {po}", f"Taxable value: {taxable}", f"CGST: {cgst}", f"SGST: {sgst}", f"Invoice total: {total}"]
    return pdf("TAX INVOICE", lines)
