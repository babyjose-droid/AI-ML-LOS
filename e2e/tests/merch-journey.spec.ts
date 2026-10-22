import { basicJourney } from './basic-journey'

basicJourney({
  title: 'Merchant & retailer loan journey (recorded): kirana store, bank-statement turnover, field visit above ₹3 lakh, sanction, KFS, disbursement',
  intro: 'Merchant & retailer loan: a kirana store owner needs stock for the festive season',
  product: 'MERCH', name: 'Ganesh Sonawane', pan: 'GANPS3127S', mobile: '9876500911', dob: '1984-03-12', gender: 'MALE', city: 'Nashik', pincode: '422401',
  address: 'Shop 4, Main Road, Deolali', business: 'Sonawane Kirana Stores', industry: 'KIRANA', vintage: '11', income: '90000', expenses: '30000',
  account: '50200911223344', ifsc: 'HDFC0001234', amount: '400000', tenure: '24', purpose: 'Festive-season stock',
  amountCaption: '₹4,00,000 for 24 months (product: ₹50,000 to ₹10 lakh, 6 to 36 months; field visit above ₹3 lakh)',
  kyc: [['PAN', 'merch-ganesh-pan.png'], ['AADHAAR', 'merch-ganesh-aadhaar.png'], ['UDYAM', 'merch-ganesh-udyam.png']],
  fieldNote: 'Store at Deolali main road visited: good stock, UPI QR and POS in use, daily footfall steady; owner present.',
  decisionCaption: 'Kirana scorecard on the bank-statement turnover (Account Aggregator), bureau and GST',
  kfsCaption: 'KFS: EMI, all-in APR and charges. Repeat customers become eligible for top-ups in Growth',
  end: 'Disbursed to the merchant’s account. End of the merchant & retailer loan walkthrough.',
})
