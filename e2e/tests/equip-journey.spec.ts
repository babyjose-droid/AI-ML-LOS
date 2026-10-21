import { assetJourney } from './asset-journey'

// Equipment & machinery finance: backhoe loader for a construction contractor; installation certificate after disbursement.
assetJourney({
  title: 'Equipment finance journey (recorded): backhoe loader, dealer invoice AI, asset checks, field visit, sanction, payout to dealer, installation certificate',
  intro: 'Step 1 · Sales captures a loan for a backhoe loader (Equipment & machinery finance) from an empanelled dealer',
  product: 'EQUIP', name: 'Vikas Shinde', pan: 'VIKPS5274S', mobile: '9876500622', dob: '1984-01-03', gender: 'MALE',
  city: 'Nashik', pincode: '422101', address: 'Survey 88, Sinnar Road', business: 'Shinde Infra Works', industry: 'SERVICES', vintage: '7',
  income: '290000', expenses: '70000', account: '50200622334455', ifsc: 'HDFC0001234',
  amount: '2643000', tenure: '60', purpose: 'JCB 3DX backhoe loader for road and site works',
  amountCaption: '₹26,43,000 for 60 months: invoice ₹33,63,000 less ₹7,20,000 margin (equipment needs at least 20%)',
  kyc: [['PAN', 'af-vikas-pan.png'], ['AADHAAR', 'af-vikas-aadhaar.png'], ['UDYAM', 'af-vikas-udyam.png']],
  dealer: 'Deccan Earthmovers', year: '2026', prefix: 'equip', insurance: true,
  assetCaption: 'Invoice AI: ₹28,50,000 + GST ₹5,13,000 = ₹33,63,000, machine serial number, insurance noting the lender',
  field: true, fieldNote: 'Visited the site office; two running road contracts with the zilla parishad seen; operator hired; machine yard confirmed.',
  memo: true, post: [['INSTALLATION_CERT', 'equip-installation.pdf'], ['INSURANCE_HP', 'equip-insurance-hp.pdf']], lienAfter: 'marked',
  end: 'Installation certificate and insurance hypothecation received: lien marked. End of the equipment finance walkthrough.',
})
