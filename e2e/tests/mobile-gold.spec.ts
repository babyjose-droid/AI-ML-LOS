import { expect, test } from '@playwright/test'
import { say } from './asset-journey'
import { back, captions, decide, login, logout, newApplication, openApp, phone, runChecks, sanctionToDisbursement } from './mobile-helpers'

/**
 * Recorded gold loan on the mobile app: the sales officer captures the loan and declares the gold, operations enters the
 * day's gold rate and runs the checks, the credit officer weighs and tests each item at the branch counter on the phone
 * (net gold weight × purity at the day's rate, RBI LTV caps), Collateral AI passes, then decision, sanction, KFS and
 * disbursement, all on the phone. Fictional borrower and SPECIMEN documents.
 */
phone({ latitude: 20.0118, longitude: 73.7826 })   // a branch in Nashik (fictional)
const NAME = 'Anita Pawar'

test('Mobile gold loan (recorded): gold declared, day’s rate, appraisal on the phone, Collateral AI, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)

  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Gold loan on the phone: a school teacher pledges a chain, a pair of bangles and a ring', 2200)
  await newApplication(page, {
    product: 'Gold loan', amount: '150000', tenure: '12', purpose: 'Daughter’s college admission', name: NAME, pan: 'ANIPP6120Q', mobile: '9876531801',
    dob: '1982-02-14', gender: 'Female', address: 'Flat 4, Indira Nagar', city: 'Nashik', pincode: '422009', business: 'Nashik Public School', industry: 'Salaried',
    vintage: '14', income: '42000', expenses: '16000', account: '50100318011223', ifsc: 'HDFC0001234',
    docs: [['Aadhaar', 'mob-anita-aadhaar.png'], ['PAN', 'mob-anita-pan.png']],
    captions: { loan: '₹1,50,000 for 12 months, interest monthly and the principal at the end', docs: 'Aadhaar and PAN photographed on the phone (specimens)' },
  })
  await page.getByTestId('go-collateral').click()
  await page.getByTestId('declare').click()
  await page.getByTestId('col-description').fill('Gold chain, a pair of bangles and a ring')
  await say(page, 'Declare the gold pledged as security', 1200)
  await page.getByTestId('declare-save').click()
  await expect(page.getByTestId('ok')).toContainText('declared')
  await expect(page.getByTestId('collateral-case')).toBeVisible()
  await say(page, 'Gold case opened: it is weighed, tested and valued at the branch', 1600)
  await back(page)
  await logout(page)

  // operations: today's gold rate, then the checks
  await login(page, 'ops1', 'Operations')
  await openApp(page, NAME)
  await page.getByTestId('go-collateral').click()
  await page.getByTestId('gold-rate-input').fill('11300')
  await say(page, 'Operations enters today’s 22 carat gold rate: the lower of today’s rate and the 30-day average is used (RBI)', 2200)
  await page.getByTestId('gold-rate-save').click()
  await expect(page.getByTestId('gold-rate')).toContainText('Gold rate saved')
  await back(page)
  await logout(page)
  await runChecks(page, NAME, 'Operations runs the automated checks: documents, KYC, bank, bureau, fraud')

  // credit officer: the appraisal at the counter
  await login(page, 'co1', 'Credit officer')
  await openApp(page, NAME)
  await page.getByTestId('go-collateral').click()
  await say(page, 'The credit officer weighs and tests each item at the counter, on the phone', 1600)
  const items: [string, string, string, string][] = [['Gold chain', '22.6', '0', '22 ct'], ['Bangles (pair)', '28.4', '0.6', '22 ct'], ['Ring with stone', '5.2', '1.1', '18 ct']]
  for (const [i, [n, gross, stones, ct]] of items.entries()) {
    if (i > 0) await page.getByTestId('gold-add').click()
    await page.getByTestId(`gold-item-${i + 1}`).fill(n)
    await page.getByTestId(`gold-gross-${i + 1}`).fill(gross)
    await page.getByTestId(`gold-stones-${i + 1}`).fill(stones)
    await page.getByTestId(`gold-purity-${i + 1}`).getByRole('radio', { name: ct, exact: true }).click()
  }
  await say(page, 'Chain 22.6 g (22 ct), bangles 28.4 g less 0.6 g stones (22 ct), ring 5.2 g less 1.1 g stone (18 ct)', 2400)
  await page.getByTestId('gold-save').click()
  await expect(page.getByTestId('ok')).toContainText('Gold valued')
  await page.getByTestId('rerun-ai').click()
  await expect(page.getByTestId('collateral-ai')).toContainText(/pass/i, { timeout: 30_000 })
  await expect(page.getByTestId('max-exposure')).toBeVisible()
  await say(page, 'Valued on the gold content only; LTV within the RBI cap; Collateral AI passes and the gold-appraisal step completes', 2800)
  await back(page)
  await logout(page)

  await decide(page, NAME, 'Decision on the phone: score, recommended amount and reasons')
  await sanctionToDisbursement(page, NAME, {
    note: 'Gold appraised and within the RBI LTV cap; salaried borrower; sanction as recommended',
    kfs: 'KFS: interest monthly, the principal in one bullet at the end',
    end: 'Disbursed. End of the mobile gold loan walkthrough',
  })
})
