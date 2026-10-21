import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded used-vehicle (CV) loan walkthrough: a secured loan on a used truck, from application to disbursement.
 * The video (test-results/cv-journey-.../video.webm) is the recording shared with the business; captions at the
 * bottom of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  permissions: ['geolocation'],
  geolocation: { latitude: 19.96012, longitude: 73.73015, accuracy: 15 }, // where the truck is inspected, for the photos
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Gurpreet Sandhu'

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

test('Used vehicle loan journey (recorded): application, KYC, vehicle inspection, valuation, collateral AI, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application and the vehicle
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a secured loan against a used commercial vehicle (a 2019 truck)')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('CV-SEC')
  await page.locator('input[name=loanAmount]').fill('600000')
  await page.locator('input[name=tenureMonths]').fill('36')
  await page.locator('input[name=purpose]').fill('Refinance and working capital for the truck')
  await say(page, '₹6,00,000 for 36 months (product range ₹1 lakh to ₹20 lakh, 12 to 48 months)', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('GURPS6614S')
  await page.locator('input[name=mobile]').fill('9876500551')
  await page.locator('input[name=dob]').fill('1983-02-11')
  await page.locator('select[name=gender]').selectOption('MALE')
  await page.locator('input[name=city]').fill('Nashik')
  await page.locator('input[name=pincode]').fill('422010')
  await page.locator('input[name=address]').fill('Plot 7, Ambad Link Road')
  await say(page, 'Owner-driver’s details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Sandhu Roadlines')
  await page.locator('select[name=industry]').selectOption('TRANSPORT')
  await page.locator('input[name=businessVintageYears]').fill('8')
  await page.locator('input[name=declaredMonthlyIncome]').fill('140000')
  await page.locator('input[name=essentialExpenses]').fill('40000')
  await say(page, 'Transport business, 8 years; the transport scorecard is used', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50200998877665')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Account for the disbursement and the borrower’s consents', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await say(page, 'Review everything, then submit')
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')

  await openSection(page, 'Documents')
  await say(page, 'Upload PAN and Aadhaar (specimens)', 1200)
  for (const [t, f] of [['PAN', 'gurpreet-pan.png'], ['AADHAAR', 'gurpreet-aadhaar.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }

  // the vehicle offered as security
  await openSection(page, 'Collateral')
  await say(page, 'Declare the truck as security (hypothecation)', 1200)
  await page.locator('[data-action=declareCollateral]').click()
  const form = page.locator('.panel', { has: page.getByRole('heading', { name: 'Declare collateral' }) })
  await form.getByLabel('Type').selectOption('VEHICLE')
  await form.getByLabel('Description').fill('Tata LPT 1109 truck, 2019')
  await form.getByLabel('Owner as declared').fill(NAME)
  await form.getByLabel('Address').fill('Sandhu Roadlines yard, Ambad, Nashik')
  await form.getByLabel('PIN code').fill('422010')
  await form.getByLabel('Latitude').fill('19.9600')
  await form.getByLabel('Longitude').fill('73.7300')
  await form.getByLabel('Registration no.').fill('MH15HH4521')
  await form.getByLabel('Make and model').fill('Tata LPT 1109')
  await form.getByLabel('Year of manufacture').fill('2019')
  await form.getByLabel(/New price/).fill('1650000')
  await say(page, 'Registration, make, year and today’s new price (for the AI value cross-check)', 1400)
  await page.getByRole('button', { name: 'Declare and open the case' }).click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()

  await page.getByRole('tab', { name: 'Documents' }).click()
  const docs: [string, string, Record<string, unknown>][] = [
    ['RC', 'cv-rc.png', { owner: NAME, registrationNo: 'MH15HH4521', chassisNo: 'MAT447018K3P21456', makeModel: 'Tata LPT 1109', manufactureYear: 2019, assignedTo: 'None' }],
    ['INSURANCE', 'cv-insurance.png', { insurer: 'New India Assurance', policyNo: 'NIA-31/2026/884512', sumInsured: 920000, validTill: '2027-08-15', owner: NAME }],
    ['VALUATION_REPORT', 'cv-valuation.png', { marketValue: 900000, valuer: 'Er. Prakash Deshmukh' }],
  ]
  await say(page, 'Upload the RC, the motor insurance and the valuation report', 1200)
  const docRows = page.locator('.panel.flush table tbody tr')
  for (const [i, [type, file, fields]] of docs.entries()) {
    const up = page.locator('.panel', { has: page.getByRole('heading', { name: 'Upload a document' }) })
    await up.locator('select').first().selectOption(type)
    await up.locator('input[type=file]').setInputFiles(fixture(file))
    await up.getByLabel(/Fields you have checked/).fill(JSON.stringify(fields))
    await up.getByRole('button', { name: 'Upload and read' }).click()
    await expect(docRows).toHaveCount(i + 1, { timeout: 30_000 })
  }
  await say(page, 'RC in the borrower’s name with no hypothecation; insurance valid to Aug 2027', 2200)
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: automated checks
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank, bureau, fraud', 1400)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
  await say(page, 'Checks done. The decision waits for the vehicle inspection and valuation, and the field visit', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 3. field officer: inspect the vehicle and visit the business
  await login(page, 'field1', 'Field officer')
  await say(page, 'Step 3 · The field officer inspects the truck: front, rear, side, odometer and chassis plate')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Vehicle photos' }).click()
  const photos: [string, string][] = [['FRONT', 'front'], ['REAR', 'rear'], ['SIDE', 'side'], ['ODOMETER', 'odometer'], ['CHASSIS_PLATE', 'chassis']]
  for (const [i, [view, f]] of photos.entries()) {
    const add = page.locator('.panel', { has: page.getByRole('heading', { name: 'Add a photo' }) })
    await add.locator('select').first().selectOption(view)
    await add.locator('input[type=file]').setInputFiles(fixture(`cv-photo-${f}.jpg`))
    await add.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator('.panel.flush table tbody tr')).toHaveCount(i + 1, { timeout: 30_000 })
  }
  await say(page, '5 photos taken at the vehicle (GPS checked), none reused from another file', 2400)

  await openApp(page)
  await page.locator('[data-action=fieldVisit]').click()
  await page.locator('textarea[name=note]').fill('Visited the transport office at Ambad; truck on contract with a cement dealer, logbook and trip receipts seen.')
  await say(page, 'Field visit notes recorded', 1000)
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'FIELD')).toHaveText('Done')
  await logout(page)

  // ---------------------------------------------------------------- 4. valuer: empanelled valuation
  await login(page, 'valuer1', 'Empanelled valuer')
  await say(page, 'Step 4 · The empanelled valuer records the vehicle valuation')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
  await page.getByLabel('Valuer').fill('Er. Prakash Deshmukh (empanelled)')
  await page.getByLabel('Market value (₹)').fill('900000')
  await page.getByLabel('Realisable value (₹)').fill('810000')
  await page.getByLabel('Distress value (₹)').fill('720000')
  await page.getByLabel('Method').fill('Inspection and market comparables')
  await page.locator('[data-action=saveValuation]').click()
  await expect(page.getByText('VAL-', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
  await say(page, 'Value ₹9,00,000 at 75% LTV: maximum exposure ₹6,75,000 against ₹6,00,000 asked', 2600)
  await logout(page)

  // ---------------------------------------------------------------- 5. credit: collateral AI, decision, memo
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 5 · Credit checks the Collateral AI result, then the decision and the credit memo')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Risk & decision' }).click()
  await page.getByRole('button', { name: 'Run Collateral AI again' }).click()
  await page.waitForTimeout(1500)
  await expect(page.getByText('Risk vector')).toBeVisible()
  await say(page, 'Collateral rules: owner on RC, no hypothecation, chassis consistent, valuation fresh, photos and LTV', 2600)
  await page.getByRole('tab', { name: '360 summary' }).click()
  await expect(page.getByTestId('collateral-360')).toContainText(/pass/i, { timeout: 20_000 })
  await say(page, 'Collateral AI: PASS. The vehicle-valuation stage of the workflow is completed automatically', 2600)

  await openApp(page)
  const run = page.locator('[data-action=runDecision]')
  if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
  await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision engine on the transport scorecard: probability of default, band, affordability and the rules', 2800)
  await openSection(page, 'Credit memo')
  await say(page, 'AI-drafted credit memo, with the vehicle and its LTV', 1600)
  const signMemo = page.locator('[data-action=signMemo]')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }
  await logout(page)

  // ---------------------------------------------------------------- 6. credit manager: sanction
  await login(page, 'cm1', 'Credit manager')
  await openApp(page)
  const sanctionState = (await state(page, 'SANCTION').innerText()).trim()
  await say(page, `Step 6 · Sanction by the authority the delegation matrix routes the case to (${sanctionState})`)
  await page.locator('[data-action=sanction]').click()
  await page.locator('textarea[name=note]').fill('Collateral PASS within LTV; transport income verified; sanction as recommended')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Sanctioned with the condition: hypothecation endorsed on the RC and the insurance. KFS with the all-in APR', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 7. borrower accepts the KFS
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 7 · The borrower accepts the KFS with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await logout(page)

  // ---------------------------------------------------------------- 8. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 8 · The loan documents: agreement, hypothecation deed, sanction letter, KFS', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated from the approved templates with this application’s data', 2000)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed: penny-drop, payout and UTR; the truck is on the loan book as security', 2600)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the used vehicle loan walkthrough.', 3000)
})
