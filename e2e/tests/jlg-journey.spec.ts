import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Recorded group-loan (JLG) walkthrough: a microfinance income-generation loan with household cash flow, CGT and GRT.
 * The video (test-results/jlg-journey-.../video.webm) is the recording shared with the business; captions at the bottom
 * of the screen say who is acting and what is being checked. Borrower and documents are fictional (SPECIMEN).
 */
test.use({
  viewport: { width: 1366, height: 768 },
  video: { mode: 'on', size: { width: 1366, height: 768 } },
  launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] },
})

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)
const NAME = 'Sunita Gaikwad'

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
test('Group loan (JLG) journey (recorded): application, household, CGT, GRT, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(12 * 60_000)
  await captions(page)

  // ---------------------------------------------------------------- 1. sales: capture the application
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Step 1 · Sales captures a JLG income-generation loan for a woman dairy farmer in a joint liability group')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('JLG')
  await page.locator('input[name=loanAmount]').fill('40000')
  await page.locator('input[name=tenureMonths]').fill('12')
  await page.locator('input[name=purpose]').fill('Buy a second milch cow')
  await say(page, 'Group loan ₹40,000 for 12 months, repaid fortnightly (product range ₹10,000 to ₹1,00,000)', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=applicantName]').fill(NAME)
  await page.locator('input[name=pan]').fill('SUNPG7742A')
  await page.locator('input[name=mobile]').fill('9876500661')
  await page.locator('input[name=dob]').fill('1988-07-02')
  await page.locator('select[name=gender]').selectOption('FEMALE')
  await page.locator('input[name=city]').fill('Sinnar')
  await page.locator('input[name=pincode]').fill('422103')
  await page.locator('input[name=address]').fill('House 21, Shivaji Nagar')
  await say(page, 'Member details as on PAN and Aadhaar', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=businessName]').fill('Dairy (2 cows)')
  await page.locator('select[name=industry]').selectOption('DAIRY')
  await page.locator('input[name=businessVintageYears]').fill('5')
  await page.locator('input[name=declaredMonthlyIncome]').fill('15000')
  await page.locator('input[name=essentialExpenses]').fill('9000')
  await say(page, 'Dairy income ₹15,000 a month; the dairy scorecard is used', 1400)
  await page.getByRole('button', { name: 'Continue' }).click()
  await page.locator('input[name=bankAccountNo]').fill('50100445566778')
  await page.locator('input[name=bankIfsc]').fill('HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await say(page, 'Her own bank account for the disbursement, and her consents', 1200)
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByTestId('application-review')).toContainText(NAME)
  await say(page, 'Review everything, then submit')
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: new RegExp(NAME) })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')
  await say(page, 'Submitted: Application 360 opens with the stepper')

  await openSection(page, 'Documents')
  await say(page, 'Upload PAN and Aadhaar (specimens)', 1200)
  for (const [t, f] of [['PAN', 'sunita-pan.png'], ['AADHAAR', 'sunita-aadhaar.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
  }

  // household cash flow: the unit for microfinance (RBI 2022)
  await openSection(page, 'Customer')
  const hh = page.locator('[data-household]')
  await hh.scrollIntoViewIfNeeded()
  await say(page, 'In microfinance the household repays: capture its members, income, expenses and existing loans', 1800)
  await hh.locator('[data-action=editHousehold]').click()
  await hh.locator('input[name=hhMembers]').fill('4')
  await hh.locator('input[name=hhExpenses]').fill('9000')
  await hh.locator('input[name=hhEmi]').fill('1500')
  await hh.getByLabel('Member name').nth(0).fill(NAME)
  await hh.getByLabel('Occupation').nth(0).fill('Dairy')
  await hh.getByLabel('Monthly income').nth(0).fill('15000')
  await hh.getByRole('button', { name: 'Add earning member' }).click()
  await hh.getByLabel('Member name').nth(1).fill('Ramdas Gaikwad')
  await hh.getByLabel('Occupation').nth(1).fill('Farm labour')
  await hh.getByLabel('Monthly income').nth(1).fill('9000')
  await say(page, 'Two earners: ₹24,000 a month, existing loan repayments ₹1,500', 1400)
  await hh.getByRole('button', { name: 'Save household' }).click()
  await expect(hh).toContainText('qualifies', { timeout: 15_000 })
  await say(page, 'Household income ₹2.88 lakh a year: qualifies as microfinance; repayments must stay within 50% of income', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 2. operations: automated checks
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank data, bureau, fraud and group-network links', 1400)
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
  await say(page, 'Checks done. The decision waits for the group stages: CGT by the field officer, then GRT by credit', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 3. field officer: compulsory group training (CGT)
  await login(page, 'field1', 'Field officer')
  await say(page, 'Step 3 · The field officer forms the group and holds the compulsory group training (CGT)')
  await openApp(page)
  await expect(page.locator('[data-stage=cgt]')).toHaveAttribute('data-stage-status', /ACTIVE|PENDING/)
  await page.locator('[data-stage-mine]').click()
  await page.locator('[data-stage-complete=cgt]').click()
  await page.locator('input[name=stage_groupName]').fill('Savitribai Mahila Gat')
  await page.locator('input[name=stage_members]').fill('5')
  await page.locator('input[name=stage_sessionsHeld]').fill('3')
  await page.locator('textarea[name=stageNote]').fill('Five women from Shivaji Nagar formed the group; three training sessions on joint liability and repayment')
  await say(page, 'Group name, 5 members, 3 training sessions', 1400)
  await page.locator('[data-stage-save]').click()
  await expect(page.locator('[data-stage=cgt]')).toHaveAttribute('data-stage-status', 'DONE')
  await say(page, 'CGT recorded')
  await logout(page)

  // ---------------------------------------------------------------- 4. credit: group recognition test (GRT), decision
  await login(page, 'co1', 'Credit officer')
  await say(page, 'Step 4 · The credit officer runs the group recognition test (GRT)')
  await openApp(page)
  await page.locator('[data-stage-mine]').click()
  await page.locator('[data-stage-complete=grt]').click()
  await page.locator('input[name=stage_membersPresent]').fill('5')
  await page.locator('textarea[name=stageNote]').fill('All five members present; each explained the loan, the fortnightly repayment and joint liability')
  await say(page, 'All 5 members present and understand the loan', 1400)
  await page.locator('[data-stage-save]').click()
  await expect(page.locator('[data-stage=grt]')).toHaveAttribute('data-stage-status', 'DONE')
  await page.reload()
  await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
  await say(page, 'GRT done: the decision engine runs')
  await openSection(page, 'Decision')
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await say(page, 'Decision on the dairy scorecard, with the household affordability rule (repayments within 50% of household income)', 3000)
  await openSection(page, 'Credit memo')
  await expect(page.locator('pre.memo')).toContainText('CREDIT MEMO')
  await say(page, 'AI-drafted credit memo, including the household and the group', 1600)
  const signMemo = page.locator('[data-action=signMemo]')
  if (await signMemo.isVisible()) { await signMemo.click(); await say(page, 'Credit memo signed and locked', 1200) }
  await logout(page)

  // ---------------------------------------------------------------- 5. sanction (the product auto-sanctions up to ₹50,000 when approved)
  await login(page, 'cm1', 'Credit manager')
  await openApp(page)
  const sanctionState = (await state(page, 'SANCTION').innerText()).trim()
  if (sanctionState.startsWith('Pending')) {
    await say(page, `Step 5 · Sanction by the credit manager (${sanctionState})`)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Group trained and tested; household within the microfinance cap; sanction as recommended')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
  } else {
    await say(page, `Step 5 · ${sanctionState}: the product auto-sanctions approved group loans up to ₹50,000`, 2400)
  }
  await openSection(page, 'Sanction & KFS')
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await say(page, 'Key Fact Statement with the fortnightly repayment schedule and the all-in APR', 2800)
  await logout(page)

  // ---------------------------------------------------------------- 6. the member accepts the KFS
  await login(page, 'sales1', 'Sales officer')
  await openApp(page)
  await say(page, 'Step 6 · The member hears the KFS in Marathi and accepts it with an OTP')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await say(page, 'KFS accepted')
  await logout(page)

  // ---------------------------------------------------------------- 7. operations: signing pack and disbursement
  await login(page, 'ops1', 'Operations')
  await openApp(page)
  await openSection(page, 'Signing pack')
  await say(page, 'Step 7 · The group-loan documents from the approved templates', 1400)
  const make = page.locator('[data-action^=make-]:not([disabled])').first()
  if (await make.isVisible()) {
    await make.click()
    await page.waitForTimeout(2500)
    await say(page, 'Documents generated with this application’s data', 2000)
  }
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
  await expect(state(page, 'APP')).toHaveText('Disbursed')
  await say(page, 'Disbursed straight to her own bank account: penny-drop, payout and UTR', 2600)
  await openSection(page, 'History')
  await say(page, 'Every step is in the audit history. End of the group loan walkthrough.', 3000)
})
