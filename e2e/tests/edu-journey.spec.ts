import { expect } from '@playwright/test'
import { pdfFile, planJourney } from './plan-journey'

// Education loan: a parent borrows for a child's nursing course; fees go to the institution term by term.
planJourney({
  title: 'Education loan journey (recorded): admission and fee letter AI, approved institution, sanction, first term fee to the institution, fee demand, second term',
  intro: 'Step 1 · Sales captures an education loan: a parent borrows for her daughter’s B.Sc Nursing fees',
  product: 'EDU', name: 'Meena Patwardhan', pan: 'MEEPP4471P', mobile: '9876500822', dob: '1976-09-15', gender: 'FEMALE', city: 'Nashik', pincode: '422006',
  address: 'Flat 9, Ashwamegh Nagar', business: 'Nashik Public School', industry: 'SALARIED', vintage: '14', income: '85000', expenses: '30000',
  account: '50100822334455', ifsc: 'HDFC0001234', amount: '320000', tenure: '24', purpose: 'B.Sc Nursing fees for the first year',
  amountCaption: '₹3,20,000 for 24 months: the first year’s fees, paid to the institute in two terms',
  kyc: [['PAN', 'edu-meena-pan.png'], ['AADHAAR', 'edu-meena-aadhaar.png'], ['SALARY_SLIP', 'edu-meena-salary.png']],
  planTitle: 'Course & fees', disbTitle: 'First term fee',
  docs: [
    { type: 'ADMISSION_LETTER', file: 'admission-letter.pdf', title: 'ADMISSION LETTER', issuer: 'Kalaram Institute of Nursing Sciences (fictional)',
      lines: ['Student: Aditi Patwardhan', 'Institution: Kalaram Institute of Nursing Sciences', 'Course: B.Sc Nursing', 'Start date: 01-08-2026', 'Duration: 4 years', 'Admission No: KINS/26/0412'] },
    { type: 'FEE_LETTER', file: 'fee-letter.pdf', title: 'FEE STRUCTURE 2026-27', issuer: 'Kalaram Institute of Nursing Sciences (fictional)',
      lines: ['Student: Aditi Patwardhan', 'Institution: Kalaram Institute of Nursing Sciences', 'Academic year: 2026-27', 'Term 1 fee: 1,60,000', 'Term 2 fee: 1,60,000', 'Total fees: 3,20,000'] },
  ],
  selects: [['institutionId', 'Kalaram Institute']],
  fields: [['relationship', 'PARENT'], ['studentDob', '2008-03-12']],
  tranches: [['Term 1 fee', '160000'], ['Term 2 fee', '160000']],
  planCaption: 'Admission and fee letters read by AI: student, institute, course, start, duration and term-wise fees',
  checksCaption: 'Plan checks: approved institute with a fee account, admission matches the student, loan within fees, releases to the institute, course within tenure',
  sanctioner: ['cm1', 'Credit manager'],
  kfsCaption: 'KFS with the EMI; fees are paid straight to the institute, term by term',
  disbCaption: 'First term fee ₹1,60,000 paid to Kalaram Institute of Nursing Sciences, not to the borrower',
  release: {
    who: ['ops1', 'Operations'],
    caption: 'Next term: the institute sends its fee demand; operations uploads it (AI reads term and amount)',
    act: async page => {
      await page.getByLabel('Fee demand').setInputFiles(pdfFile({ type: 'FEE_DEMAND', file: 'fee-demand-term2.pdf', title: 'FEE DEMAND', issuer: 'Kalaram Institute of Nursing Sciences (fictional)',
        lines: ['Student: Aditi Patwardhan', 'Institution: Kalaram Institute of Nursing Sciences', 'Term: 2', 'Amount due: 1,60,000', 'Due date: 15-01-2027'] }))
      await page.getByRole('button', { name: 'Upload fee demand' }).click()
      await expect(page.getByRole('status')).toContainText('uploaded and read')
    },
    then: 'Term 2 fee ₹1,60,000 released to the institute against its demand: the whole year is paid',
  },
  end: 'Both terms paid to the institute; repayment by EMI. End of the education loan walkthrough.',
})
