import { pledgeJourney } from './pledge-journey'

pledgeJourney({
  title: 'Loan against securities journey (recorded): CAS read by AI, drawing power, depository pledge, disbursement, price fall, margin call cured',
  intro: 'Loan against securities: a salaried investor borrows against shares and mutual funds instead of selling them',
  product: 'LAS', name: 'Ashok Naik', pan: 'ASHPN4421N', mobile: '9876500977', dob: '1980-04-29', gender: 'MALE', city: 'Nashik', pincode: '422005',
  address: 'Flat 301, Parijat Nagar', business: 'Western Ghats Power Ltd', vintage: '15', income: '120000', expenses: '40000',
  account: '50100977889900', ifsc: 'HDFC0001234', amount: '700000', tenure: '12', purpose: 'Home renovation without selling investments',
  amountCaption: '₹7,00,000 for 12 months: interest monthly, principal at the end',
  kyc: [['PAN', 'las-ashok-pan.png'], ['AADHAAR', 'las-ashok-aadhaar.png']],
  doc: { type: 'CAS_STATEMENT', file: 'cas-statement.pdf', title: 'CONSOLIDATED ACCOUNT STATEMENT', issuer: 'Depository (fictional specimen)',
    lines: ['Investor: Ashok Naik', 'Depository: CDSL', 'BO ID: 1208160012345678', 'Holdings as on statement date:',
      'INE0DM101015   Western Ghats Power Ltd (demo)   Qty: 400', 'INE0DM201013   Konkan Pharma Ltd (demo)   Qty: 600',
      'INF0DM501018   Sahyadri Bluechip Equity Fund - Growth (demo)   Units: 3,250.500', 'INE0DM401019   Satpuda Speculative Mining Ltd (demo)   Qty: 5000'] },
  readCaption: 'CAS read by AI: holder, CDSL, BO ID and four holdings priced from the approved list and today’s prices',
  checksCaption: 'Drawing power ₹7.5 lakh: shares and equity funds at 50% LTV; the unapproved mining share does not count; no single security above half',
  kfsCaption: 'KFS: interest monthly, principal at the end; a margin call if the value falls',
  lien: { how: 'ref', ref: 'CDSL-PSN-2610-55120', caption: 'Operations marks the pledge in CDSL (pledge sequence number) before paying' },
  disbCaption: 'Disbursed after the pledge was marked; the loan book shows ₹7 lakh outstanding',
  margin: {
    prices: 'INE0DM101015,1050\nINE0DM201013,735\nINF0DM501018,131.5',
    dropCaption: 'Prices fell about 15%: drawing power is now below the ₹7 lakh outstanding; a margin call gives 7 days to cure',
    isin: 'Godavari Short Term Debt', quantity: '3000', ref: 'CDSL-PSN-2610-55188',
    cureCaption: 'The borrower pledges 3,000 debt-fund units (75% LTV): drawing power is back above the loan and the call is cured',
  },
  end: 'Pledges & margin: value, drawing power and LTV of every pledge after each price file. End of the loan against securities walkthrough.',
})
