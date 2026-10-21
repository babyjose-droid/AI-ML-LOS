import { assetJourney } from './asset-journey'

// Commercial vehicle finance (new truck): MSME transport operator, dealer payout, RC and insurance hypothecation after disbursement.
assetJourney({
  title: 'Commercial vehicle finance journey (recorded): new truck, dealer invoice AI, asset checks, field visit, sanction, payout to dealer, RC hypothecation',
  intro: 'Step 1 · Sales captures a loan for a new truck (Commercial vehicle finance), bought from an empanelled dealer',
  product: 'CV-NEW', name: 'Mahesh Yadav', pan: 'MAHPY4182Y', mobile: '9876500611', dob: '1981-06-14', gender: 'MALE',
  city: 'Nashik', pincode: '422010', address: 'Gat 41, MIDC Ambad', business: 'Yadav Freight Carriers', industry: 'TRANSPORT', vintage: '9',
  income: '260000', expenses: '60000', account: '50200611223344', ifsc: 'HDFC0001234',
  amount: '2422200', tenure: '60', purpose: 'New Tata LPT 1918 truck for a cement transport contract',
  amountCaption: '₹24,22,200 for 60 months: the on-road price less the borrower’s ₹4,80,000 down payment',
  kyc: [['PAN', 'af-mahesh-pan.png'], ['AADHAAR', 'af-mahesh-aadhaar.png'], ['UDYAM', 'af-mahesh-udyam.png']],
  dealer: 'Sahyadri Commercial Motors', year: '2026', prefix: 'cv-new', insurance: true,
  assetCaption: 'Invoice AI read the invoice: ₹22,40,000 + GST ₹6,27,200 + ₹35,000 = ₹29,02,200; chassis and engine numbers; insurance with the lender noted',
  field: true, fieldNote: 'Visited the transport office at Ambad; fleet of 3 trucks, contract with a cement dealer seen; parking yard confirmed.',
  memo: true, post: [['RC_HP', 'cv-new-rc.pdf'], ['INSURANCE_HP', 'cv-new-insurance-hp.pdf']], lienAfter: 'marked',
  end: 'RC and insurance show the lender’s hypothecation: lien marked. End of the commercial vehicle walkthrough.',
})
