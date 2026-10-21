import { day } from './pdf'
import { tradeJourney } from './trade-journey'

// Dealer & distributor finance: the lender pays the anchor for the dealer's purchase; the dealer repays within the credit period.
tradeJourney({
  title: 'Dealer finance journey (recorded): anchor dealer programme, revolving limit, anchor invoice AI, payment to anchor, dealer repays',
  intro: 'Step 1 · Sales sets up a dealer finance limit for a paint dealer of Sahyadri Paints',
  product: 'DDF', name: 'Anand Gawande', pan: 'ANAPG3318L', mobile: '9876500733', dob: '1982-05-30', city: 'Nashik', pincode: '422005',
  address: 'Shop 3, Canada Corner', business: 'Gawande Paint House', industry: 'TRADING', vintage: '11', income: '320000', expenses: '70000',
  account: '50200733445566', ifsc: 'HDFC0001234', limit: '800000', purpose: 'Inventory purchases from Sahyadri Paints',
  limitCaption: 'An ₹8 lakh dealer limit for 12 months: purchases from the anchor paid by the lender',
  kyc: [['PAN', 'tf-anand-pan.png'], ['AADHAAR', 'tf-anand-aadhaar.png'], ['UDYAM', 'tf-anand-udyam.png']],
  gstin: '27ANAPG3318L1Z6', anchor: 'Sahyadri Paints',
  structureCaption: 'Dealer programme: 100% of the anchor’s invoice, up to 60 days, 5% first-loss guarantee from the anchor',
  invoice: { file: 'spl-invoice-7781.pdf', no: 'SPL/2026/7781', date: day(-2), due: day(43), po: 'GPH-ORD-2291', seller: 'Sahyadri Paints Ltd',
    sellerGstin: '27AABCS8890M1Z2', buyer: 'Gawande Paint House', buyerGstin: '27ANAPG3318L1Z6', taxable: '4,00,000', cgst: '36,000', sgst: '36,000', total: '4,72,000' },
  readCaption: 'Invoice AI: the anchor is the seller and the dealer the buyer; ₹4,00,000 + GST ₹72,000 = ₹4,72,000, due in 43 days',
  confirmBy: 'Sahyadri Paints', confirmRef: 'SPL-DMS-DISPATCH-4410',
  fundCaption: 'Funded 100% (₹4,72,000) and paid to Sahyadri Paints, not the dealer; stock goes to the dealer on credit',
  repayCaption: 'The dealer repays from sales within the credit period',
  end: 'An overdue dealer stops new drawdowns (stop supply) until it pays. End of the dealer finance walkthrough.',
})
