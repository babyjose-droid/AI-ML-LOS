import { basicJourney } from './basic-journey'

basicJourney({
  title: 'Doctor & professional loan journey (recorded): clinic expansion, professional income, CRO sanction above ₹10 lakh, KFS, disbursement',
  intro: 'Doctor & professional loan: a paediatrician expands the clinic',
  product: 'PROF', name: 'Nilesh Deore', pan: 'NILPD2208D', mobile: '9876500933', dob: '1983-08-08', gender: 'MALE', city: 'Nashik', pincode: '422013',
  address: 'Deore Child Clinic, Gangapur Road', business: 'Deore Child Clinic', industry: 'SERVICES', vintage: '9', income: '260000', expenses: '60000',
  account: '50200933445566', ifsc: 'HDFC0001234', amount: '2000000', tenure: '48', purpose: 'Clinic expansion and equipment',
  amountCaption: '₹20 lakh for 48 months (product: ₹2 lakh to ₹50 lakh, no field visit, professional income)',
  kyc: [['PAN', 'prof-nilesh-pan.png'], ['AADHAAR', 'prof-nilesh-aadhaar.png']],
  decisionCaption: 'Professional income from the bank statement, bureau and affordability; above ₹10 lakh the CRO sanctions',
  kfsCaption: 'KFS for the professional loan: EMI, all-in APR, foreclosure and penal charges',
  end: 'Disbursed to the doctor’s account. End of the doctor & professional loan walkthrough.',
})
