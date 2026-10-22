import { day } from './pdf'
import { pledgeJourney } from './pledge-journey'

pledgeJourney({
  title: 'Loan against fixed deposit journey (recorded): FD receipt read by AI, 90% of the deposit, lien confirmation AI, disbursement',
  intro: 'Loan against a fixed deposit: the borrower needs funds without breaking her deposit',
  product: 'FDL', name: 'Kavita Rane', pan: 'KAVPR2905R', mobile: '9876500988', dob: '1985-12-11', gender: 'FEMALE', city: 'Nashik', pincode: '422002',
  address: 'Flat 7, Tidke Colony', business: 'Nashik Textiles Ltd', vintage: '10', income: '60000', expenses: '25000',
  account: '50100988990011', ifsc: 'HDFC0001234', amount: '400000', tenure: '24', purpose: 'Short-term needs without breaking the deposit',
  amountCaption: '₹4,00,000 for 24 months against a ₹5 lakh deposit',
  kyc: [['PAN', 'fdl-kavita-pan.png'], ['AADHAAR', 'fdl-kavita-aadhaar.png']],
  doc: { type: 'FD_RECEIPT', file: 'fd-receipt.pdf', title: 'FIXED DEPOSIT RECEIPT', issuer: 'Fictional Co-operative Bank Ltd (specimen)',
    lines: ['Bank: Fictional Co-operative Bank Ltd', 'FD No: FCB/FD/2026/88123', 'Depositor: Kavita Rane', 'Principal: 5,00,000', 'Rate of interest: 7.25%',
      'Date of deposit: ' + day(-120), 'Maturity date: ' + day(1000)] },
  readCaption: 'Deposit receipt read by AI: bank, FD number, depositor, ₹5 lakh at 7.25%, maturity in about 33 months',
  checksCaption: 'Checks: deposit identified, held by the borrower, loan within 90% of the deposit with interest to date, deposit outlasts the loan',
  kfsCaption: 'KFS: interest monthly, principal at the end or from the deposit at maturity',
  lien: { how: 'doc', caption: 'The bank confirms the lien in the lender’s favour: operations uploads the letter and AI reads the lien reference',
    doc: { file: 'lien-letter.pdf', title: 'LIEN CONFIRMATION', issuer: 'Fictional Co-operative Bank Ltd (specimen)',
      lines: ['Deposit: FCB/FD/2026/88123', 'Depositor: Kavita Rane', 'Lien ref: FCB/LIEN/26/4411', 'In favour of: Rhythm Finance Ltd', 'Lien date: ' + day(0)] } },
  disbCaption: 'Lien marked, then disbursed: the deposit stays and keeps earning interest',
  end: 'End of the loan against fixed deposit walkthrough.',
})
