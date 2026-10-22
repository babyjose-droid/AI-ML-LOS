import { basicJourney } from './basic-journey'

basicJourney({
  title: 'Micro-enterprise working capital journey (recorded): small ticket, no field visit up to ₹1 lakh, sanction, KFS, disbursement',
  intro: 'Micro-enterprise working capital: a tailoring unit needs cloth and thread for a school-uniform order',
  product: 'MEWC', name: 'Rekha Thorat', pan: 'REKPT6650T', mobile: '9876500922', dob: '1989-11-25', gender: 'FEMALE', city: 'Nashik', pincode: '422002',
  address: 'Room 12, Sant Kabir Nagar', business: 'Rekha Tailoring Works', industry: 'SERVICES', vintage: '5', income: '32000', expenses: '12000',
  account: '50100922334455', ifsc: 'HDFC0001234', amount: '80000', tenure: '12', purpose: 'Cloth and thread for a uniform order',
  amountCaption: '₹80,000 for 12 months (product: ₹25,000 to ₹3 lakh, 6 to 18 months; no field visit up to ₹1 lakh)',
  kyc: [['PAN', 'mewc-rekha-pan.png'], ['AADHAAR', 'mewc-rekha-aadhaar.png']],
  decisionCaption: 'Small-ticket decision on bank cash flows and the bureau; affordability keeps the EMI within income',
  kfsCaption: 'KFS for a small working-capital loan: EMI, APR and charges in the borrower’s language',
  end: 'Disbursed the same day. End of the micro-enterprise working capital walkthrough.',
})
