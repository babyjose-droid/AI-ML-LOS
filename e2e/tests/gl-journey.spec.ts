import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded gold loan walkthrough: gold jewellery appraised in the branch at the day's rate, from application to disbursement.
 * The video (test-results/gl-journey-.../video.webm) is the recording shared with the business; captions at the
 * bottom of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Shobha Kale'

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

test('Gold loan journey (recorded): application, gold rate, appraisal, collateral AI, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(12 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application and the gold
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a gold loan: a teacher pledges a chain, a pair of bangles and a ring')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('GL')
  await page.locator('input[name=loanAmount]').fill('200000')
  await page.locator('input[name=tenureMonths]').fill('12')
  await page.locator('input[name=purpose]').fill('Child’s college fees')
  await say(page, '₹2,00,000 for 12 months, repaid in one bullet at the end (product range ₹10,000 to ₹10 lakh, 3 to 12 months)', 1600)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('SHOPK2208K')
  await page.locator('input[name=mobile]').fill('9876500441')
  await page.locator('input[name=dob]').fill('1979-09-05')
  await page.locator('select[name=gender]').selectOption('FEMALE')
  await page.locator('input[name=city]').fill('Nashik')
  await page.locator('input[name=pincode]').fill('422007')
  await page.locator('input[name=address]').fill('Flat 12, Mahatma Nagar')
  await say(page, 'Borrower’s details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Nashik Public School')
  await page.locator('select[name=industry]').selectOption('SALARIED')
  await page.locator('input[name=businessVintageYears]').fill('12')
  await page.locator('input[name=declaredMonthlyIncome]').fill('45000')
  await page.locator('input[name=essentialExpenses]').fill('18000')
  await say(page, 'Teacher, 12 years in service, ₹45,000 a month', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50100332211009')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Her bank account and her consents', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')
  await say(page, 'Submitted: Application 360 opens with the stepper')

  await openSection(page, 'Documents')
  await say(page, 'Upload PAN and Aadhaar (specimens)', 1200)
  for (const [t, f] of [['PAN', 'shobha-pan.png'], ['AADHAAR', 'shobha-aadhaar.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }

  await openSection(page, 'Collateral')
  await say(page, 'Declare the gold pledged as security', 1200)
  await page.locator('[data-action=declareCollateral]').click()
  const form = page.locator('.panel', { has: page.getByRole('heading', { name: 'Declare collateral' }) })
  await form.getByLabel('Type').selectOption('GOLD')
  await form.getByLabel('Description').fill('Gold chain, a pair of bangles and a ring')
  await page.getByRole('button', { name: 'Declare and open the case' }).click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
  await say(page, 'Gold case opened: it will be weighed, tested and valued in the branch', 1800)
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: today's gold rate and the automated checks
  await login(page, 'ops1', 'Operations')
  await say(page, 'Step 2 · Operations enters today’s gold rate (22 carat, per gram) in the rate master')
  await page.goto('/collateral?tab=policy')
  await page.getByLabel('Gold rate').fill('11300')
  await page.locator('.panel', { has: page.getByRole('heading', { name: /Gold rate/ }) }).getByRole('button', { name: 'Save' }).click()
  await page.waitForTimeout(1200)
  await say(page, 'The rate used is the lower of the 30-day average and today’s rate (RBI)', 2400)
  await openApp(page)
  await say(page, 'Automated checks: documents, KYC, bank, bureau, fraud', 1200)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await say(page, 'Checks done. The decision waits for the gold appraisal', 2200)
  await logout(page)

  // ---------------------------------------------------------------- 3. credit officer: appraise the gold in the branch
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 3 · The credit officer weighs and tests each item in the branch')
  await openCollateral(page)
  await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
  const items: [string, string, string, string][] = [['Gold chain', '25.4', '0', '22'], ['Bangles (pair)', '31.2', '0.6', '22'], ['Ring with stone', '6.8', '1.2', '18']]
  for (const [i, [name, gross, stones, karat]] of items.entries()) {
    if (i > 0) await page.getByRole('button', { name: 'Add item' }).click()
    await page.getByPlaceholder('Item').nth(i).fill(name)
    await page.getByPlaceholder('Gross g').nth(i).fill(gross)
    await page.getByPlaceholder('Stones g').nth(i).fill(stones)
    await page.getByLabel('Purity').nth(i).selectOption(karat)
  }
  await say(page, 'Chain 25.4 g (22 ct), bangles 31.2 g less 0.6 g stones (22 ct), ring 6.8 g less 1.2 g stone (18 ct)', 2400)
  await page.locator('[data-action=saveValuation]').click()
  await expect(page.getByText('VAL-', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
  await say(page, 'Valued on the gold content only, never the stones or making charges; LTV within the RBI caps', 2800)
  await page.getByRole('tab', { name: 'Risk & decision' }).click()
  await page.getByRole('button', { name: 'Run Collateral AI again' }).click()
  await page.waitForTimeout(1500)
  await page.getByRole('tab', { name: '360 summary' }).click()
  await expect(page.getByTestId('collateral-360')).toContainText(/pass/i, { timeout: 20_000 })
  await say(page, 'Collateral AI: PASS. The gold-appraisal stage of the workflow is completed automatically', 2600)

  await openApp(page)
  const run = page.locator('[data-action=runDecision]')
  if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
  await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision engine: probability of default, band and the rules', 2600)
  const signMemo = page.locator('[data-action=signMemo]')
  await openSection(page, 'Credit memo')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }
  await logout(page)

  // ---------------------------------------------------------------- 4. credit manager: sanction
  await login(page, 'cm1', 'Credit manager')
  await openApp(page)
  const sanctionState = (await state(page, 'SANCTION').innerText()).trim()
  if (sanctionState.startsWith('Pending')) {
    await say(page, `Step 4 · Sanction by the credit manager (${sanctionState})`)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Gold appraised and within the RBI LTV cap; salaried borrower; sanction as recommended')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  } else {
    await say(page, `Step 4 · ${sanctionState}`)
  }
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Sanctioned with the condition: gold sealed in a tamper-proof packet and kept in the vault. KFS with a bullet repayment', 3000)
  await logout(page)

  // ---------------------------------------------------------------- 5. borrower accepts the KFS
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 5 · The borrower accepts the KFS with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await logout(page)

  // ---------------------------------------------------------------- 6. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 6 · The gold-loan documents: pledge form, sanction letter, KFS', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated from the approved templates with this application’s data', 2000)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed to her bank account: penny-drop, payout and UTR', 2600)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the gold loan walkthrough.', 3000)
})
