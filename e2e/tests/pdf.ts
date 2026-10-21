/**
 * A minimal text-layer PDF (Helvetica, one A4 page) built in the test, so documents can carry dates relative to the day the
 * test runs (invoice date, due date). Marked SPECIMEN; for fictional test data only.
 */
export function makePdf(title: string, lines: string[], issuer = ''): Buffer {
  const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)').replace(/[^\x20-\x7e]/g, '')
  let content = `BT /F2 15 Tf 50 800 Td (${esc(title)}) Tj ET\nBT /F1 10 Tf 50 783 Td (${esc(issuer)}) Tj ET\n`
  content += `BT /F2 14 Tf 0.8 0.2 0.2 rg 450 812 Td (SPECIMEN) Tj ET\nBT /F1 11 Tf 0 0 0 rg 50 750 Td 18 TL\n`
  content += lines.map((l, i) => (i ? 'T* ' : '') + `(${esc(l)}) Tj`).join('\n') + '\nET\n'
  content += `BT /F1 8 Tf 50 60 Td (Fictional specimen for software testing. Not a real document.) Tj ET\n`
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>',
    `<< /Length ${Buffer.byteLength(content, 'latin1')} >>\nstream\n${content}endstream`,
  ]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objs.forEach((o, i) => { offsets.push(Buffer.byteLength(out, 'latin1')); out += `${i + 1} 0 obj\n${o}\nendobj\n` })
  const xref = Buffer.byteLength(out, 'latin1')
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offsets.map(o => String(o).padStart(10, '0') + ' 00000 n \n').join('')
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

/** dd-mm-yyyy, `days` from today. */
export function day(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return `${String(d.getDate()).padStart(2, '0')}-${String(d.getMonth() + 1).padStart(2, '0')}-${d.getFullYear()}`
}

/** A tax invoice as the trade-finance tests submit it. */
export function tradeInvoice(o: { no: string; date: string; due: string; po?: string; seller: string; sellerGstin: string; buyer: string; buyerGstin: string
  taxable: string; cgst: string; sgst: string; total: string }): Buffer {
  return makePdf('TAX INVOICE', [`Supplier: ${o.seller}`, `Supplier GSTIN: ${o.sellerGstin}`, `Buyer: ${o.buyer}`, `Buyer GSTIN: ${o.buyerGstin}`,
    `Invoice No: ${o.no}`, `Invoice date: ${o.date}`, `Due date: ${o.due}`, ...(o.po ? [`PO No: ${o.po}`] : []), `Taxable value: ${o.taxable}`,
    `CGST: ${o.cgst}`, `SGST: ${o.sgst}`, `Invoice total: ${o.total}`], o.seller)
}
