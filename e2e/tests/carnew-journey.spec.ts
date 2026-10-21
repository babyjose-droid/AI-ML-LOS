import { assetJourney } from './asset-journey'

// New vehicle (car) loan: salaried borrower, dealer payout, RC and insurance hypothecation.
assetJourney({
  title: 'New car loan journey (recorded): salaried borrower, dealer invoice AI, asset checks, sanction, payout to dealer, RC hypothecation',
  intro: 'Step 1 · Sales captures a new car loan for a salaried borrower at the dealership',
  product: 'CAR-NEW', name: 'Neha Kulkarni', pan: 'NEHPK3348K', mobile: '9876500633', dob: '1990-08-21', gender: 'FEMALE',
  city: 'Nashik', pincode: '422013', address: 'Flat 5, Gangapur Road', business: 'Nashik Pharma Ltd', industry: 'SALARIED', vintage: '6',
  income: '115000', expenses: '35000', account: '50100633445566', ifsc: 'HDFC0001234',
  amount: '921400', tenure: '60', purpose: 'New Maruti Suzuki Brezza for personal use',
  amountCaption: '₹9,21,400 for 60 months: on-road price ₹11,51,400 less ₹2,30,000 down payment (80% loan-to-cost)',
  kyc: [['PAN', 'af-neha-pan.png'], ['AADHAAR', 'af-neha-aadhaar.png'], ['SALARY_SLIP', 'af-neha-salary.png']],
  dealer: 'Nashik Auto Hub', year: '2026', prefix: 'car-new', insurance: true,
  assetCaption: 'Invoice AI: ₹8,80,000 + GST ₹2,46,400 + ₹25,000 = ₹11,51,400, 17-character chassis (VIN) and engine number',
  field: false, memo: true, post: [['RC_HP', 'car-new-rc.pdf'], ['INSURANCE_HP', 'car-new-insurance-hp.pdf']], lienAfter: 'marked',
  end: 'Registered with the lender’s hypothecation on the RC: lien marked. End of the new car loan walkthrough.',
})
