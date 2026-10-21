import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded LAP walkthrough: a new loan against property from application to disbursement, every role in turn.
 * The video (test-results/lap-journey-.../video.webm) is the recording shared with the business; captions at the
 * bottom of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  permissions: ['geolocation'],
  geolocation: { latitude: 20.00505, longitude: 73.76505, accuracy: 15 }, // at the property, for the site photos
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Sanjay Kulkarni'

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
async function openCollateral(page: Page) {
  await page.goto('/collateral?tab=cases')
  await page.locator('table tbody tr', { hasText: NAME }).first().click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
}

test('LAP journey (recorded): application, KYC, collateral AI, valuation, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a new Loan Against Property application in five short steps')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('LAP')
  await page.locator('input[name=loanAmount]').fill('1500000')
  await page.locator('input[name=tenureMonths]').fill('120')
  await page.locator('input[name=purpose]').fill('Expansion of hardware shop')
  await say(page, 'Product, amount ₹15,00,000 and tenure 120 months', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('SANPK7788K')
  await page.locator('input[name=mobile]').fill('9876500771')
  await page.locator('input[name=dob]').fill('1978-06-14')
  await page.locator('select[name=gender]').selectOption('MALE')
  await page.locator('input[name=city]').fill('Nashik')
  await page.locator('input[name=pincode]').fill('422005')
  await page.locator('input[name=address]').fill('Flat 3, Sai Residency, College Road')
  await say(page, 'Applicant details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Kulkarni Hardware')
  await page.locator('select[name=industry]').selectOption('TRADING')
  await page.locator('input[name=businessVintageYears]').fill('9')
  await page.locator('input[name=declaredMonthlyIncome]').fill('180000')
  await page.locator('input[name=essentialExpenses]').fill('40000')
  await say(page, 'Business, income and household expenses', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50200987654321')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Disbursement account and the borrower’s consents (KYC, Account Aggregator, bureau)', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await say(page, 'Review everything, then submit')
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')
  await say(page, 'Submitted: Application 360 opens with the 8-stage stepper')

  await openSection(page, 'Documents')
  await say(page, 'Upload the KYC and business documents: PAN, Aadhaar, Udyam (specimens)', 1200)
  for (const [t, f] of [['PAN', 'sanjay-pan.png'], ['AADHAAR', 'sanjay-aadhaar.png'], ['UDYAM', 'sanjay-udyam.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }
  await say(page, 'Document AI read each document; Aadhaar is masked to the last 4 digits')

  // the property offered as security
  await openSection(page, 'Collateral')
  await say(page, 'Declare the property offered as security', 1200)
  await page.locator('[data-action=declareCollateral]').click()
  const form = page.locator('.panel', { has: page.getByRole('heading', { name: 'Declare collateral' }) })
  await form.getByLabel('Type').selectOption('RESIDENTIAL')
  await form.getByLabel('Description').fill('Bungalow, College Road')
  await form.getByLabel('Owner as declared').fill(NAME)
  await form.getByLabel('Address').fill('Bungalow 12, College Road, Nashik')
  await form.getByLabel('PIN code').fill('422005')
  await form.getByLabel('Latitude').fill('20.0050')
  await form.getByLabel('Longitude').fill('73.7650')
  await form.getByLabel('Survey / CTS no.').fill('CTS 2210/4')
  await form.getByLabel('Municipal ID').fill('NMC-55120')
  await form.getByLabel('Area (sq ft)').fill('1600')
  await page.getByRole('button', { name: 'Declare and open the case' }).click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
  await say(page, 'Collateral case opened: Collateral AI will check documents, title, photos and valuation')

  await page.getByRole('tab', { name: 'Documents' }).click()
  const today = new Date()
  const ecTo = new Date(today.getTime() - 10 * 86_400_000).toISOString().slice(0, 10)
  const docs: [string, string, Record<string, unknown>, unknown[]?][] = [
    ['SALE_DEED', 'lap-sale-deed.png', { owner: NAME, seller: 'Madhav Joshi', surveyNo: 'CTS2210/4', areaSqft: 1600, registrationNo: '5120/2015', registrationDate: '2015-09-10' }],
    ['EC', 'lap-ec.png', { periodFrom: '2010-01-01', periodTo: ecTo, surveyNo: 'CTS2210/4' },
      [{ date: '2015-09-10', type: 'SALE', docNo: '5120/2015', from: 'Madhav Joshi', to: NAME, linkedRef: null }]],
    ['KHATA', 'lap-khata.png', { owner: NAME, municipalId: 'NMC-55120' }],
    ['PROPERTY_TAX', 'lap-tax.png', { owner: NAME, municipalId: 'NMC-55120', taxPaidUpTo: '2026-27' }],
    ['VALUATION_REPORT', 'lap-valuation.png', { marketValue: 4500000, valuer: 'Er. Prakash Deshmukh' }],
  ]
  await say(page, 'Upload the property papers: sale deed, encumbrance certificate, khata, tax receipt, valuation report', 1200)
  const docRows = page.locator('.panel.flush table tbody tr')
  for (const [i, [type, file, fields, tx]] of docs.entries()) {
    const up = page.locator('.panel', { has: page.getByRole('heading', { name: 'Upload a document' }) })
    await up.locator('select').first().selectOption(type)
    await up.locator('input[type=file]').setInputFiles(fixture(file))
    await up.getByLabel(/Fields you have checked/).fill(JSON.stringify(fields))
    if (tx) await up.getByLabel(/EC entries/).fill(JSON.stringify(tx))
    await up.getByRole('button', { name: 'Upload and read' }).click()
    await expect(docRows).toHaveCount(i + 1, { timeout: 30_000 })
  }
  await say(page, 'All mandatory property documents are on file; the fields checked by the officer count as confirmed')
  await page.getByRole('tab', { name: 'EC analysis' }).click()
  await say(page, 'EC analysis: 16 years covered, no open charge')
  await page.getByRole('tab', { name: 'Title chain' }).click()
  await say(page, 'Title chain: Madhav Joshi → Sanjay Kulkarni (2015)')
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: automated checks
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank (Account Aggregator), bureau, fraud', 1200)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
  await say(page, 'Documents complete, KYC verified, data fetched. The decision waits for the field visit (LAP always needs one)', 2600)
  await openSection(page, 'Data')
  await say(page, 'KYC checks from the documents and the bank / bureau data')
  await logout(page)

  // ---------------------------------------------------------------- 3. field officer: visit and site photos
  await login(page, 'field1', 'Field officer')
  await say(page, 'Step 3 · The field officer visits the borrower and the property')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Property vision' }).click()
  await say(page, 'Site photos are taken at the property: GPS and the photo itself are checked', 1200)
  const photos: [string, string][] = [['EXTERIOR_FRONT', 'front'], ['EXTERIOR_SIDE', 'side'], ['INTERIOR_HALL', 'hall'], ['INTERIOR_KITCHEN', 'kitchen'], ['STREET_VIEW', 'street']]
  for (const [i, [view, f]] of photos.entries()) {
    const add = page.locator('.panel', { has: page.getByRole('heading', { name: 'Add a photo' }) })
    await add.locator('select').first().selectOption(view)
    await add.locator('input[type=file]').setInputFiles(fixture(`lap-photo-${f}.jpg`))
    await add.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator('.panel.flush table tbody tr')).toHaveCount(i + 1, { timeout: 30_000 })
  }
  await say(page, '5 photos, all views covered, all within 250 m of the property, none reused from another file', 2600)

  await openApp(page)
  await page.locator('[data-action=fieldVisit]').click()
  await page.locator('textarea[name=note]').fill('Visited shop and residence. Business running, good stock, neighbours confirm 9 years at the location.')
  await say(page, 'Field visit notes recorded', 1000)
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'FIELD')).toHaveText('Done')
  await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
  await say(page, 'Visit done: the decision engine runs on the full facts')
  await logout(page)

  // ---------------------------------------------------------------- 4. valuer: empanelled valuation
  await login(page, 'valuer1', 'Empanelled valuer')
  await say(page, 'Step 4 · The empanelled valuer records the valuation')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
  await page.getByLabel('Valuer').fill('Er. Prakash Deshmukh (empanelled)')
  await page.getByLabel('Market value (₹)').fill('4500000')
  await page.getByLabel('Realisable value (₹)').fill('4050000')
  await page.getByLabel('Distress value (₹)').fill('3375000')
  await page.locator('[data-action=saveValuation]').click()
  await expect(page.getByText('VAL-', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
  await say(page, 'Value used ₹45 L, 10% haircut, 65% LTV: maximum exposure about ₹26.3 L against ₹15 L asked', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 5. credit: decision, memo, collateral AI
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 5 · Credit reviews the decision, the credit memo and the Collateral AI result')
  await openApp(page)
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision engine: probability of default, score band, affordability and the rules', 2600)
  await openSection(page, 'Credit memo')
  await say(page, 'AI-drafted credit memo', 1200)
  const signMemo = page.locator('[data-action=signMemo]')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }

  await openCollateral(page)
  await page.getByRole('tab', { name: 'Risk & decision' }).click()
  await page.getByRole('button', { name: 'Run Collateral AI again' }).click()
  await page.waitForTimeout(1500)
  await expect(page.getByText('Risk vector')).toBeVisible()
  await say(page, 'Collateral rules CR-01 to CR-11 and the 10-dimension risk vector', 2600)
  await page.getByRole('tab', { name: '360 summary' }).click()
  await expect(page.getByTestId('collateral-360')).toContainText(/pass/i, { timeout: 20_000 })
  await say(page, 'Collateral AI: PASS · unified credit + collateral decision', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 6. CRO: sanction
  await login(page, 'cro1', 'CRO')
  await say(page, 'Step 6 · Sanction by the authority the delegation matrix routes the case to')
  await openApp(page)
  await page.locator('[data-action=sanction]').click()
  await page.locator('textarea[name=note]').fill('Collateral PASS within LTV; income and bureau support the recommended amount')
  await say(page, 'Sanctioning the recommended amount', 1000)
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Sanctioned: the Key Fact Statement shows the all-in APR, EMI schedule and charges', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 7. borrower accepts the KFS (with sales)
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 7 · The borrower reads the KFS in their language and accepts it with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await say(page, 'KFS accepted: cooling-off period starts')
  await logout(page)

  // ---------------------------------------------------------------- 8. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 8 · The loan documents for this product: agreement, MODTD (mortgage), sanction letter and more', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated from the approved templates with this application’s data', 2200)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed: penny-drop check, payout and UTR; the loan moves to the loan book', 2600)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the LAP walkthrough.', 3000)
})
