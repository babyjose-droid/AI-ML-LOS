import { assetJourney } from './asset-journey'

// Medical equipment finance: a doctor's clinic buying an ultrasound; supplier payout; installation and insurance after disbursement.
assetJourney({
  title: 'Medical equipment loan journey (recorded): supplier invoice AI, asset checks, sanction, payout to supplier, installation certificate',
  intro: 'Step 1 · Sales captures a loan for a portable ultrasound for a diagnostic clinic (Medical equipment finance)',
  product: 'MED-EQ', name: 'Farah Shaikh', pan: 'FARPS8126S', mobile: '9876500655', dob: '1985-04-27', gender: 'FEMALE',
  city: 'Nashik', pincode: '422005', address: 'Shaikh Diagnostic Clinic, College Road', business: 'Shaikh Diagnostic Clinic', industry: 'SERVICES', vintage: '6',
  income: '220000', expenses: '60000', account: '50200655667788', ifsc: 'HDFC0001234',
  amount: '1257000', tenure: '48', purpose: 'Portable ultrasound for the clinic',
  amountCaption: '₹12,57,000 for 48 months: invoice ₹15,27,000 less ₹2,70,000 margin. Below ₹25 lakh, so no field visit',
  kyc: [['PAN', 'af-farah-pan.png'], ['AADHAAR', 'af-farah-aadhaar.png']],
  dealer: 'MedEquip India', year: '2026', prefix: 'med-eq', insurance: true,
  assetCaption: 'Invoice AI: ₹13,50,000 + GST ₹1,62,000 + ₹15,000 = ₹15,27,000; equipment serial number; insurance noting the lender',
  field: false, memo: true, post: [['INSTALLATION_CERT', 'med-eq-installation.pdf'], ['INSURANCE_HP', 'med-eq-insurance-hp.pdf']], lienAfter: 'marked',
  end: 'Installed and insured with the lender as hypothecatee: lien marked. End of the medical equipment walkthrough.',
})
