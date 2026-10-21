import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded personal-loan walkthrough: a new salaried personal loan from application to disbursement, every role in turn.
 * The video (test-results/pl-journey-.../video.webm) is the recording shared with the business; captions at the bottom
 * of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Rohan Mehta'

// caption bar that survives page loads (kept in sessionStorage)
async function captions(page: Page) {
  await page.addInitScript(() => {
    const draw = () => {
      let el = document.getElementById('__cap')
      if (!el) {
        el = document.createElement('div')
        el.id = '__cap'
        el.setAttribute('style', 'position:fixed;left:50%;transform:translateX(-50%);bottom:14px;z-index:99999;max-width:90%;padding:10px 18px;border-radius:12px;background:rgba(20,23,31,.88);color:#fff;font:600 15px/1.4 system-ui,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.25);pointer-events:none')
        document.body.appendChild(el)
      }
      const t = sessionStorage.getItem('__cap') || ''
      el.textContent = t
      el.style.display = t ? 'block' : 'none'
    }
    ;(window as unknown as { __cap: () => void }).__cap = draw
    document.addEventListener('DOMContentLoaded', draw)
  })
}
async function say(page: Page, text: string, pause = 1800) {
  await page.evaluate(t => { sessionStorage.setItem('__cap', t); (window as unknown as { __cap?: () => void }).__cap?.() }, text)
  await page.waitForTimeout(pause)
}

async function login(page: Page, user: string, who: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await say(page, `Signing in as ${who} (${user})`, 900)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}
async function logout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
}
async function openApp(page: Page) {
  await page.goto('/applications?view=list')
  await page.getByPlaceholder('Search name, PAN or application no.').fill(NAME)
  await page.getByRole('cell', { name: NAME, exact: true }).first().click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
}
test('Personal loan journey (recorded): application, KYC, bank data, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(10 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a new Personal Loan application for a salaried borrower')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('PL')
  await page.locator('input[name=loanAmount]').fill('300000')
  await page.locator('input[name=tenureMonths]').fill('36')
  await page.locator('input[name=purpose]').fill('Home renovation')
  await say(page, 'Personal loan ₹3,00,000 for 36 months (product range ₹50,000 to ₹5,00,000, 6 to 48 months)', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('ROHPM5521M')
  await page.locator('input[name=mobile]').fill('9876500881')
  await page.locator('input[name=email]').fill('rohan.mehta@example.com')
  await page.locator('input[name=dob]').fill('1991-03-22')
  await page.locator('select[name=gender]').selectOption('MALE')
  await page.locator('input[name=city]').fill('Pune')
  await page.locator('input[name=pincode]').fill('411045')
  await page.locator('input[name=address]').fill('B-204, Green Park, Baner')
  await say(page, 'Applicant details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Pune Software Ltd')
  await page.locator('select[name=industry]').selectOption('SALARIED')
  await page.locator('input[name=businessVintageYears]').fill('6')
  await page.locator('input[name=declaredMonthlyIncome]').fill('95000')
  await page.locator('input[name=essentialExpenses]').fill('30000')
  await say(page, 'Employer, years in service, take-home pay and household expenses', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50100765432109')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Salary account for the disbursement and the borrower’s consents (KYC, Account Aggregator, bureau)', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await say(page, 'Review everything, then submit')
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')
  await say(page, 'Submitted: Application 360 opens with the 8-stage stepper')

  await openSection(page, 'Documents')
  await say(page, 'Upload PAN, Aadhaar and the latest salary slip (specimens)', 1200)
  for (const [t, f] of [['PAN', 'rohan-pan.png'], ['AADHAAR', 'rohan-aadhaar.png'], ['SALARY_SLIP', 'rohan-salary.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }
  await expect(page.locator('[data-doc=PAN] [data-field=pan]')).toHaveText('ROHPM5521M')
  await say(page, 'Document AI read each document: PAN, name, date of birth, net pay ₹95,000; Aadhaar masked to the last 4 digits', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: automated checks
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank statement (Account Aggregator), bureau, fraud, decision', 1400)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
  await expect(state(page, 'DECISION')).not.toHaveText('Pending')
  await say(page, 'All checks done in one pass. No field visit for a salaried personal loan, so the decision runs straight away', 2600)
  await openSection(page, 'Documents')
  await expect(page.locator('[data-doc=AADHAAR] [data-field=aadhaarMasked]')).toContainText('XXXX XXXX')
  await say(page, 'Documents verified against the application', 1600)
  await openSection(page, 'Data')
  await expect(page.getByText('KYC checks from documents')).toBeVisible()
  await say(page, 'KYC checks from the documents, 12 months of Account Aggregator bank data and the bureau report', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 3. credit: decision and memo
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 3 · Credit reviews the decision and the credit memo')
  await openApp(page)
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision engine: probability of default, score band, affordability (FOIR) and every policy rule', 2800)
  await openSection(page, 'Credit memo')
  await expect(page.locator('pre.memo')).toContainText('CREDIT MEMO')
  await say(page, 'AI-drafted credit memo; every number traces back to the file', 1600)
  const signMemo = page.locator('[data-action=signMemo]')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }
  await logout(page)

  // ---------------------------------------------------------------- 4. credit manager: sanction (unless the product auto-sanctioned it)
  await login(page, 'cm1', 'Credit manager')
  await openApp(page)
  const sanctionState = (await state(page, 'SANCTION').innerText()).trim()
  if (sanctionState.startsWith('Pending')) {
    await say(page, `Step 4 · Sanction: the delegation matrix routes this case to the credit manager (${sanctionState})`)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Salaried with stable credits; FOIR within policy; sanction as recommended')
    await say(page, 'Sanctioning the recommended amount', 1000)
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  } else {
    await say(page, `Step 4 · Sanction: ${sanctionState} by the product rule`)
  }
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Key Fact Statement: amount, all-in APR, EMI, fees, repayment schedule and cooling-off period', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 5. borrower accepts the KFS (with sales)
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 5 · The borrower reads the KFS in their language and accepts it with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await say(page, 'KFS accepted')
  await logout(page)

  // ---------------------------------------------------------------- 6. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 6 · The loan documents for a personal loan: application form, sanction letter, KFS, agreement', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated from the approved templates with this application’s data', 2200)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed: penny-drop name check on the salary account, payout and UTR', 2600)
  await openSection(page, 'Disbursement')
  await say(page, 'Net amount after processing fee and GST, sent to the borrower’s account', 2200)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the personal loan walkthrough.', 3000)
})
