import { basicJourney } from './basic-journey'

basicJourney({
  title: 'Franchise finance journey (recorded): café franchise set-up, field visit, CRO sanction, KFS, disbursement',
  intro: 'Franchise finance: a second café outlet under a food-chain franchise',
  product: 'FRAN', name: 'Amit Chavan', pan: 'AMIPC7741C', mobile: '9876500944', dob: '1991-01-17', gender: 'MALE', city: 'Nashik', pincode: '422005',
  address: 'Flat 22, Rajiv Nagar', business: 'Chavan Food Ventures', industry: 'SERVICES', vintage: '4', income: '180000', expenses: '50000',
  account: '50200944556677', ifsc: 'HDFC0001234', amount: '1500000', tenure: '60', purpose: 'Fit-out, kitchen equipment and franchise fee for a new outlet',
  amountCaption: '₹15 lakh for 60 months (product: ₹5 lakh to ₹50 lakh, field visit always)',
  kyc: [['PAN', 'fran-amit-pan.png'], ['AADHAAR', 'fran-amit-aadhaar.png'], ['UDYAM', 'fran-amit-udyam.png']],
  fieldNote: 'First outlet on College Road visited: running well, franchise agreement and new site lease seen; fit-out quotation checked.',
  decisionCaption: 'Decision on the first outlet’s cash flows, bureau and GST; the CRO sanctions above ₹10 lakh',
  kfsCaption: 'KFS: EMI over 5 years, all-in APR and charges',
  end: 'Disbursed. End of the franchise finance walkthrough.',
})
