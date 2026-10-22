import { basicJourney } from './basic-journey'

basicJourney({
  title: 'School & hospital finance journey (recorded): school building expansion, field visit, CRO sanction, KFS, disbursement',
  intro: 'School & hospital finance: an English-medium school adds four classrooms',
  product: 'INST', name: 'Sunanda Mahajan', pan: 'SUNPM5512M', mobile: '9876500955', dob: '1972-02-02', gender: 'FEMALE', city: 'Nashik', pincode: '422101',
  address: 'Mahajan English Medium School, Jail Road', business: 'Mahajan Education Services', industry: 'SERVICES', vintage: '16', income: '320000', expenses: '80000',
  account: '50200955667788', ifsc: 'HDFC0001234', amount: '3000000', tenure: '96', purpose: 'Four new classrooms and a science lab',
  amountCaption: '₹30 lakh for 8 years (product: ₹10 lakh to ₹50 lakh, field visit always)',
  kyc: [['PAN', 'inst-sunanda-pan.png'], ['AADHAAR', 'inst-sunanda-aadhaar.png'], ['UDYAM', 'inst-sunanda-udyam.png']],
  fieldNote: 'School visited: 640 students, fee register and bank collections seen, construction estimate for the new wing checked.',
  decisionCaption: 'Institution cash flows from fee collections, bureau and affordability; the CRO sanctions',
  kfsCaption: 'KFS: EMI over 8 years, all-in APR and charges',
  end: 'Disbursed. End of the school & hospital finance walkthrough.',
})
