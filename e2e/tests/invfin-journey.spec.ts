import { day } from './pdf'
import { tradeJourney } from './trade-journey'

// Invoice & receivables finance: an MSME supplier with sublimits on two rated buyers; a duplicate invoice is stopped.
tradeJourney({
  title: 'Invoice & receivables finance journey (recorded): buyer sublimits, revolving limit, invoice AI, buyer confirmation, funding, duplicate stopped, repayment',
  intro: 'Step 1 · Sales sets up an invoice-finance limit for a castings maker that sells to large buyers on 60-day credit',
  product: 'INV-FIN', name: 'Prakash Deshpande', pan: 'PRAPD4512K', mobile: '9876500711', dob: '1979-03-19', city: 'Nashik', pincode: '422007',
  address: 'Plot B-14, MIDC Satpur', business: 'Deshpande Precision Castings', industry: 'MANUFACTURING', vintage: '9', income: '400000', expenses: '80000',
  account: '50200711223344', ifsc: 'HDFC0001234', limit: '1000000', purpose: 'Working capital against receivables from auto-component buyers',
  limitCaption: 'A ₹10 lakh revolving limit for 12 months, drawn invoice by invoice',
  kyc: [['PAN', 'tf-prakash-pan.png'], ['AADHAAR', 'tf-prakash-aadhaar.png'], ['UDYAM', 'tf-prakash-udyam.png']],
  gstin: '27PRAPD4512K1Z3', sublimits: [['BYR-WAC', '700000'], ['BYR-DRM', '300000']],
  structureCaption: 'Sublimits on two approved buyers: Western Auto Components ₹7 lakh, Deccan Retail Mart ₹3 lakh',
  invoice: { file: 'dpc-invoice-0815.pdf', no: 'DPC/2026/0815', date: day(-3), due: day(57), po: 'WAC-PO-44812', seller: 'Deshpande Precision Castings',
    sellerGstin: '27PRAPD4512K1Z3', buyer: 'Western Auto Components Ltd', buyerGstin: '27AAWCW7731B1Z8', taxable: '5,00,000', cgst: '45,000', sgst: '45,000', total: '5,90,000' },
  readCaption: 'Invoice AI read the number, dates, PO, both GSTINs and ₹5,00,000 + GST ₹90,000 = ₹5,90,000; the buyer is matched by its GSTIN',
  confirmBy: 'Western Auto Components', confirmRef: 'WAC-VP-ACC-55120',
  fundCaption: 'Maker-checker: sales submitted, operations funded 80% = ₹4,72,000 to the supplier; interest runs to the due date',
  duplicate: true,
  repayCaption: 'On the due date the buyer pays the invoice: operations records the receipt',
  end: 'Buyers carry ratings, advance rates, tenors, concentration caps and exposure. End of the invoice finance walkthrough.',
})
