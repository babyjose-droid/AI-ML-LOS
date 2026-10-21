"""SPECIMEN asset finance documents (text-layer PDFs) for tests and demos. Fictional dealers and borrowers."""
from tools.collateral_gen import pdf


def invoice(no="SCM/INV/2026/0412", date="14-10-2026", gstin="27AAKCS4512M1Z5", make="Tata Motors", model="LPT 1918 Cowl",
            chassis="MAT448123R1K22871", engine="B5.9-180-KQ1192", base="22,40,000", cgst="3,13,600", sgst="3,13,600",
            other="35,000", total="29,02,200", buyer="Gurpreet Logistics"):
    return pdf("TAX INVOICE", [f"Dealer: Sahyadri Commercial Motors", f"GSTIN: {gstin}", f"Invoice No: {no}",
                               f"Date: {date}", f"Buyer: {buyer}", f"Make: {make}", f"Model: {model}", f"Chassis No: {chassis}",
                               f"Engine No: {engine}", f"Base price: {base}", f"CGST: {cgst}", f"SGST: {sgst}",
                               f"Other charges: {other}", f"Grand total: {total}"])




def quotation(no="SCM/Q/2026/0388", date="02-10-2026", gstin="27AAKCS4512M1Z5", make="Tata Motors", model="LPT 1918 Cowl", total="29,02,200"):
    return pdf("QUOTATION", [f"Quotation No: {no}", f"Date: {date}", f"GSTIN: {gstin}", f"Make: {make}", f"Model: {model}",
                             "Validity: 30 days", f"Total: {total}"])


def receipt(no="SCM/R/1187", date="10-10-2026", amount="4,40,000"):
    return pdf("MONEY RECEIPT", [f"Receipt No: {no}", f"Date: {date}", f"Amount received: {amount}", "Towards: margin money"])


def insurance(insurer="Fictional General Insurance Ltd", policy="FGI/CV/26/778812", idv="25,50,000", till="13-10-2027",
              hyp="Rhythm Finance Ltd"):
    return pdf("MOTOR INSURANCE POLICY", [f"Insurer: {insurer}", f"Policy No: {policy}", f"IDV: {idv}", f"Valid till: {till}",
                                          f"Hypothecation: {hyp}"])


def rc(reg="MH15 KL 4421", chassis="MAT448123R1K22871", hyp="Rhythm Finance Ltd"):
    return pdf("CERTIFICATE OF REGISTRATION", [f"Registration No: {reg}", f"Chassis No: {chassis}", f"Hypothecation: {hyp}"])
