import { day } from './pdf'
import { tradeJourney } from './trade-journey'

// Supply chain (vendor) finance: a supplier to an anchor; the anchor accepts the invoice and pays the lender on the due date.
tradeJourney({
  title: 'Supply chain finance journey (recorded): anchor vendor programme, revolving limit, invoice AI, anchor acceptance, funding, anchor repays',
  intro: 'Step 1 · Sales onboards a supplier of Godavari Agro Foods into the anchor’s vendor finance programme',
  product: 'SCF', name: 'Kiran Bhosale', pan: 'KIRPB6627B', mobile: '9876500722', dob: '1987-07-07', city: 'Niphad', pincode: '422303',
  address: 'Gat 212, Pimpalgaon Road', business: 'Bhosale Agro Supplies', industry: 'AGRI', vintage: '6', income: '250000', expenses: '60000',
  account: '50200722334455', ifsc: 'HDFC0001234', limit: '600000', purpose: 'Early payment of invoices raised on Godavari Agro Foods',
  limitCaption: 'A ₹6 lakh vendor limit for 12 months under the anchor’s programme',
  kyc: [['PAN', 'tf-kiran-pan.png'], ['AADHAAR', 'tf-kiran-aadhaar.png'], ['UDYAM', 'tf-kiran-udyam.png']],
  gstin: '27KIRPB6627B1Z1', anchor: 'Godavari Agro Foods',
  structureCaption: 'Anchor programme: 90% advance, up to 90 days, the anchor pays on the due date',
  invoice: { file: 'bas-invoice-0221.pdf', no: 'BAS/2026/0221', date: day(-3), due: day(87), po: 'GAF-PO-90213', seller: 'Bhosale Agro Supplies',
    sellerGstin: '27KIRPB6627B1Z1', buyer: 'Godavari Agro Foods Ltd', buyerGstin: '27AAGCG5521H1Z6', taxable: '4,00,000', cgst: '10,000', sgst: '10,000', total: '4,20,000' },
  readCaption: 'Invoice AI: ₹4,00,000 + GST ₹20,000 = ₹4,20,000 on Godavari Agro Foods, PO GAF-PO-90213, due in 87 days',
  confirmBy: 'Godavari Agro Foods', confirmRef: 'GAF-SCF-ACCEPT-7781',
  fundCaption: 'Anchor-accepted invoice funded at 90% (₹3,78,000) to the supplier the same day, instead of waiting 90 days',
  repayCaption: 'On the due date the anchor pays the lender directly',
  end: 'Anchors carry the programme limit, advance rate, tenor and who repays. End of the supply chain finance walkthrough.',
})
