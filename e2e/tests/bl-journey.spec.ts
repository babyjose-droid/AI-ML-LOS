import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded business-loan walkthrough: a new unsecured MSME loan with a guarantor, from application to disbursement.
 * The video (test-results/bl-journey-.../video.webm) is the recording shared with the business; captions at the bottom
 * of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Imran Qureshi'
const GUARANTOR = 'Salim Qureshi'

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
async function partyRow(page: Page) { return page.locator('tr', { hasText: GUARANTOR }) }
async function otpDialog(page: Page, finish: string) {
  const dlg = page.getByRole('dialog')
  await dlg.getByRole('button', { name: 'Send OTP to their mobile' }).click()
  await dlg.locator('input[name=party_otp]').fill('123456')
  await say(page, 'The guarantor reads out the OTP sent to his own phone', 1400)
  await dlg.getByRole('button', { name: finish }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0, { timeout: 15_000 })
}

test('Business loan journey (recorded): application, guarantor, KYC, field visit, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(12 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a new unsecured Business Loan for a small trader')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('BL-UNS')
  await page.locator('input[name=loanAmount]').fill('600000')
  await page.locator('input[name=tenureMonths]').fill('24')
  await page.locator('input[name=purpose]').fill('Stock for the festive season')
  await say(page, 'Business loan ₹6,00,000 for 24 months (product range ₹50,000 to ₹10,00,000, 6 to 36 months)', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('IMRPQ4471Q')
  await page.locator('input[name=mobile]').fill('9876500991')
  await page.locator('input[name=dob]').fill('1986-11-18')
  await page.locator('select[name=gender]').selectOption('MALE')
  await page.locator('input[name=city]').fill('Nashik')
  await page.locator('input[name=pincode]').fill('422001')
  await page.locator('input[name=address]').fill('Shop 9, Ganjmal Market')
  await say(page, 'Proprietor’s details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Qureshi Mobile Point')
  await page.locator('select[name=industry]').selectOption('TRADING')
  await page.locator('input[name=businessVintageYears]').fill('9')
  await page.locator('input[name=declaredMonthlyIncome]').fill('150000')
  await page.locator('input[name=essentialExpenses]').fill('35000')
  await expect(page.getByTestId('form-parties')).toContainText('amount > 500000')
  await say(page, 'Business, trading scorecard, income. Above ₹5 lakh this product needs a guarantor before sanction', 2400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50200111223344')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Current account for the disbursement and the borrower’s consents (KYC, Account Aggregator, bureau)', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await say(page, 'Review everything, then submit')
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')
  await say(page, 'Submitted: Application 360 opens with the 8-stage stepper')

  await openSection(page, 'Documents')
  await say(page, 'Upload PAN, Aadhaar and the Udyam registration (specimens)', 1200)
  for (const [t, f] of [['PAN', 'imran-pan.png'], ['AADHAAR', 'imran-aadhaar.png'], ['UDYAM', 'imran-udyam.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }

  // the guarantor: added, his own documents, his own consent by OTP
  await openSection(page, 'Parties')
  await expect(page.getByTestId('party-issues')).toContainText('A guarantor is required before sanction')
  await say(page, 'Co-applicants & guarantors: the rule check says a guarantor is needed', 2000)
  await page.locator('[data-party=add]').click()
  await page.locator('select[name=party_role]').selectOption('GUARANTOR')
  await page.locator('select[name=party_relationship]').selectOption('PARENT')
  await page.locator('input[name=party_name]').fill(GUARANTOR)
  await page.locator('input[name=party_pan]').fill('SALPQ3302Q')
  await page.locator('input[name=party_mobile]').fill('9876500992')
  await page.locator('input[name=party_dob]').fill('1962-04-10')
  await say(page, 'Adding the borrower’s father as guarantor', 1200)
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(await partyRow(page)).toBeVisible()
  await expect(page.getByTestId('party-issues')).not.toContainText('A guarantor is required')

  await (await partyRow(page)).getByRole('button', { name: /Documents/ }).click()
  await say(page, 'The guarantor’s own PAN and Aadhaar are read for his KYC', 1200)
  for (const [i, [t, f]] of [['PAN', 'salim-pan.png'], ['AADHAAR', 'salim-aadhaar.png']].entries()) {
    const dlg = page.getByRole('dialog')
    await dlg.locator('select').first().selectOption(t)
    await dlg.locator('input[type=file]').setInputFiles(fixture(f))
    await dlg.getByRole('button', { name: 'Upload and read' }).click()
    await expect(dlg.locator('table tbody tr')).toHaveCount(i + 1, { timeout: 30_000 })
  }
  await say(page, 'Both documents read; Aadhaar kept masked', 1400)
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).click()

  await (await partyRow(page)).getByRole('button', { name: 'Consent by OTP' }).click()
  const cdlg = page.getByRole('dialog')
  await cdlg.getByLabel(/Verify my identity/).check()
  await cdlg.getByLabel(/Check my credit history/).check()
  await say(page, 'In the branch: the guarantor’s consent for KYC and a bureau check, read to him in his language', 1600)
  await otpDialog(page, 'Record consent')
  await expect(await partyRow(page)).toContainText('given')
  await say(page, 'Guarantor’s consent recorded with his OTP')
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: automated checks
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await say(page, 'Step 2 · Operations runs the automated checks for the borrower and the guarantor', 1400)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
  await expect(state(page, 'FIELD')).toHaveText('Required')
  await say(page, 'Documents complete, KYC verified, bank and bureau fetched. Above ₹5 lakh a field visit is required first', 2600)
  await openSection(page, 'Parties')
  await expect(await partyRow(page)).toContainText('Verified')
  await say(page, 'Guarantor: KYC verified from his documents and his bureau score pulled', 2200)
  await openSection(page, 'Data')
  await say(page, 'KYC checks, 12 months of Account Aggregator bank data, GST and bureau for the business', 2200)
  await logout(page)

  // ---------------------------------------------------------------- 3. field officer: visit
  await login(page, 'field1', 'Field officer')
  await say(page, 'Step 3 · The field officer visits the shop')
  await openApp(page)
  await page.locator('[data-action=fieldVisit]').click()
  await page.locator('textarea[name=note]').fill('Shop open and busy, stock about ₹8 lakh, UPI sales seen, neighbours confirm 9 years at this market.')
  await say(page, 'Field visit notes recorded', 1000)
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'FIELD')).toHaveText('Done')
  await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
  await say(page, 'Visit done: the decision engine runs on the full facts')
  await logout(page)

  // ---------------------------------------------------------------- 4. credit: decision and memo
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 4 · Credit reviews the decision and the credit memo')
  await openApp(page)
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision engine on the trading scorecard: probability of default, band, cash-flow affordability and the rules', 2800)
  await openSection(page, 'Credit memo')
  await expect(page.locator('pre.memo')).toContainText('CREDIT MEMO')
  await say(page, 'AI-drafted credit memo, including the guarantor', 1600)
  const signMemo = page.locator('[data-action=signMemo]')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }
  await logout(page)

  // ---------------------------------------------------------------- 5. credit manager: sanction
  await login(page, 'cm1', 'Credit manager')
  await openApp(page)
  const sanctionState = (await state(page, 'SANCTION').innerText()).trim()
  if (sanctionState.startsWith('Pending')) {
    await say(page, `Step 5 · Sanction: the delegation matrix routes this case to the credit manager (${sanctionState})`)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Stable trading business, verified cash flows, guarantor in place; sanction as recommended')
    await say(page, 'Sanctioning the recommended amount', 1000)
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  } else {
    await say(page, `Step 5 · Sanction: ${sanctionState}`)
  }
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Key Fact Statement: amount, all-in APR, EMI, fees, repayment schedule and cooling-off period', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 6. borrower accepts the KFS, guarantor signs the guarantee
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 6 · The borrower accepts the KFS with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await openSection(page, 'Parties')
  await say(page, 'The guarantor signs the guarantee with an OTP on his own phone', 1400)
  await (await partyRow(page)).getByRole('button', { name: 'Sign by OTP' }).click()
  await otpDialog(page, 'Record signature')
  await expect((await partyRow(page)).getByRole('button', { name: 'Sign by OTP' })).toHaveCount(0, { timeout: 10_000 })
  await say(page, 'Guarantee signed')
  await logout(page)

  // ---------------------------------------------------------------- 7. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 7 · The loan documents for a business loan: application form, sanction letter, KFS, agreement, guarantee', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated from the approved templates with this application’s data', 2200)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed: pre-disbursal re-check (consents, guarantor signature), penny-drop, payout and UTR', 2600)
  await openSection(page, 'Disbursement')
  await say(page, 'Net amount after processing fee and GST, sent to the business account', 2200)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the business loan walkthrough.', 3000)
})
