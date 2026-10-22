import { expect, test, type Page } from '@playwright/test'
import { captions, fixture, login, logout, openApp, say, state } from './asset-journey'
import { makePdf } from './pdf'
import { openSection } from './sections'

/**
 * Shared recorded walkthrough for the loan-plan products: affordable housing (stages released with construction), education
 * (term fees paid to the institution) and farm & allied (7/12, scale of finance, half-yearly repayment after harvest).
 * Borrowers, institutions and documents are fictional (SPECIMEN); plan documents are built at run time.
 */
export interface PlanDoc { type: string; file: string; title: string; issuer: string; lines: string[]; seq?: number }
export interface PlanCase {
  title: string; intro: string; product: string
  name: string; pan: string; mobile: string; dob: string; gender: 'MALE' | 'FEMALE'; city: string; pincode: string; address: string
  business: string; industry: string; vintage: string; income: string; expenses: string; account: string; ifsc: string
  amount: string; tenure: string; purpose: string; amountCaption: string
  kyc: [string, string][]
  planTitle: string                      // stage 4 name
  disbTitle: string                      // stage 8 name
  fields: [string, string][]             // [input or select name, value]
  selects?: [string, string][]           // [select name, option text]
  tranches?: [string, string, string?][] // [label, amount, stage %]
  docs: PlanDoc[]
  planCaption: string
  checksCaption: string
  afterPlan?: (page: Page) => Promise<void>      // sales, same session (e.g. the property)
  field?: (page: Page) => Promise<void>          // field officer session (logged in)
  valuer?: (page: Page) => Promise<void>         // valuer session (logged in)
  credit?: (page: Page) => Promise<void>         // extra credit-officer steps
  sanctioner: [string, string]
  kfsCaption: string
  disbCaption: string
  release?: { who: [string, string]; caption: string; act: (page: Page) => Promise<void>; then: string }
  end: string
  geo?: { latitude: number; longitude: number }  // where site photos are taken
}

export const pdfFile = (d: PlanDoc) => ({ name: d.file, mimeType: 'application/pdf', buffer: makePdf(d.title, d.lines, d.issuer) })

export function planJourney(c: PlanCase) {
  test.use({
    viewport: { width: 1366, height: 768 },
    video: { mode: 'on', size: { width: 1366, height: 768 } },
    launchOptions: { slowMo: 120 },
    actionTimeout: 30_000,
    ...(c.geo ? { permissions: ['geolocation'], geolocation: { ...c.geo, accuracy: 15 } } : {}),
  })

  test(c.title, async ({ page }) => {
    test.setTimeout(15 * 60_000)
    await captions(page)

    // ---------------------------------------------------------------- 1. sales: application, KYC, the plan
    await login(page, 'sales1', 'Sales officer')
    await say(page, c.intro, 2200)
    await page.getByRole('link', { name: 'New application' }).click()
    await page.locator('select[name=productCode]').selectOption(c.product)
    await page.locator('input[name=loanAmount]').fill(c.amount)
    await page.locator('input[name=tenureMonths]').fill(c.tenure)
    await page.locator('input[name=purpose]').fill(c.purpose)
    await say(page, c.amountCaption, 1800)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=applicantName]').fill(c.name)
    await page.locator('input[name=pan]').fill(c.pan)
    await page.locator('input[name=mobile]').fill(c.mobile)
    await page.locator('input[name=dob]').fill(c.dob)
    await page.locator('select[name=gender]').selectOption(c.gender)
    await page.locator('input[name=city]').fill(c.city)
    await page.locator('input[name=pincode]').fill(c.pincode)
    await page.locator('input[name=address]').fill(c.address)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=businessName]').fill(c.business)
    await page.locator('select[name=industry]').selectOption(c.industry)
    await page.locator('input[name=businessVintageYears]').fill(c.vintage)
    await page.locator('input[name=declaredMonthlyIncome]').fill(c.income)
    await page.locator('input[name=essentialExpenses]').fill(c.expenses)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=bankAccountNo]').fill(c.account)
    await page.locator('input[name=bankIfsc]').fill(c.ifsc)
    for (const k of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${k}]`).check()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByTestId('application-review')).toContainText(c.name)
    await page.getByRole('button', { name: 'Save and submit' }).click()
    await expect(page.getByRole('heading', { name: new RegExp(c.name) })).toBeVisible()
    await expect(state(page, 'APP')).toHaveText('Submitted')
    await expect(page.locator('.phases button[data-phase="4"]')).toContainText(c.planTitle)
    await expect(page.locator('.phases button[data-phase="8"]')).toContainText(c.disbTitle)
    await say(page, `Submitted. Stage 4 is "${c.planTitle}"; stage 8 is "${c.disbTitle}"`, 2000)

    await openSection(page, 'Documents')
    for (const [t, f] of c.kyc) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('File').setInputFiles(fixture(f))
      await page.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
    }
    await say(page, 'KYC and income documents uploaded (specimens)', 1000)

    await openSection(page, 'Plan')
    await say(page, c.planCaption, 2200)
    for (const d of c.docs.filter(x => !x.seq)) {
      await page.getByLabel('Plan document type').selectOption(d.type)
      await page.getByLabel('Plan document', { exact: true }).setInputFiles(pdfFile(d))
      await page.locator('[data-plan]').getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.getByRole('status')).toContainText('uploaded and read', { timeout: 30_000 })
      await expect(page.locator(`[data-plan-doc="${d.type}"]`).first()).toBeVisible()
    }
    for (const [k, v] of c.selects ?? []) {
      const val = await page.locator(`select[name=${k}] option`, { hasText: v }).first().getAttribute('value')
      await page.locator(`select[name=${k}]`).selectOption(val!)
    }
    for (const [k, v] of c.fields) {
      const el = page.locator(`[data-plan] [name="${k}"]`)
      if ((await el.evaluate(e => e.tagName)) === 'SELECT') await el.selectOption(v); else await el.fill(v)
    }
    if (c.tranches) for (const [i, [l, a, p]] of c.tranches.entries()) {
      if (!(await page.locator(`input[name="tr-label-${i + 1}"]`).isVisible())) await page.locator('[data-action=add-tranche]').click()
      await page.locator(`input[name="tr-label-${i + 1}"]`).fill(l)
      await page.locator(`input[name="tr-amount-${i + 1}"]`).fill(a)
      if (p !== undefined) await page.locator(`input[name="tr-pct-${i + 1}"]`).fill(p)
    }
    await page.getByRole('button', { name: 'Save and check' }).click()
    await expect(page.getByRole('status')).toContainText('Plan saved', { timeout: 20_000 })
    await expect(page.locator('[data-plan-status]')).toHaveAttribute('data-plan-status', 'PASS')
    await page.locator('[data-plan-check]').first().scrollIntoViewIfNeeded()
    await say(page, c.checksCaption, 3000)
    if (c.afterPlan) await c.afterPlan(page)
    await logout(page)

    // ---------------------------------------------------------------- 2. operations: automated checks
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank data, bureau, fraud', 1400)
    await page.locator('[data-action=process]').click()
    await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
    await expect(state(page, 'DOCS')).toHaveText('Complete')
    await expect(state(page, 'KYC')).toHaveText('Verified')
    await logout(page)

    if (c.field) {
      await login(page, 'field1', 'Field officer')
      await c.field(page)
      await logout(page)
    }
    if (c.valuer) {
      await login(page, 'valuer1', 'Empanelled valuer')
      await c.valuer(page)
      await logout(page)
    }

    // ---------------------------------------------------------------- 3. credit
    await login(page, 'co1', 'Credit officer')
    await openApp(page, c.name)
    const run = page.locator('[data-action=runDecision]')
    if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
    await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
    await openSection(page, 'Decision')
    await expect(page.getByText('What drove the risk')).toBeVisible()
    await say(page, 'Credit reviews the decision: probability of default, affordability and every rule', 2200)
    await openSection(page, 'Credit memo')
    const sign = page.locator('[data-action=signMemo]')
    if (await sign.isVisible()) { await sign.click(); await say(page, 'Credit memo signed', 900) }
    if (c.credit) await c.credit(page)
    await logout(page)

    // ---------------------------------------------------------------- 4. sanction and KFS
    await login(page, c.sanctioner[0], c.sanctioner[1])
    await openApp(page, c.name)
    await say(page, `Sanction by the ${c.sanctioner[1]}: the plan checks must pass first`, 1400)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Plan checks pass; income supports the repayment; sanction as recommended')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
    await openSection(page, 'Sanction & KFS')
    await expect(page.getByText('APR (all-in)')).toBeVisible()
    await say(page, c.kfsCaption, 2600)
    await logout(page)
    await login(page, 'sales1', 'Sales officer')
    await openApp(page, c.name)
    await page.locator('[data-action=acceptKfs]').click()
    await page.locator('input[name=otp]').fill('123456')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
    await say(page, 'The borrower accepts the KFS by OTP', 1000)
    await logout(page)

    // ---------------------------------------------------------------- 5. operations: disbursement (first release)
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Signing pack')
    const make = page.locator('[data-action^=make-]:not([disabled])').first()
    if (await make.isVisible()) { await make.click(); await page.waitForTimeout(2500) }
    await page.locator('[data-action=disburse]').click()
    await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
    await expect(state(page, 'APP')).toHaveText('Disbursed')
    await say(page, c.disbCaption, 2600)
    if (c.tranches) {
      await openSection(page, 'Plan')
      await expect(page.locator('[data-tranche="1"] [data-tranche-status]')).toHaveAttribute('data-tranche-status', 'PAID')
      await page.locator('[data-releases]').scrollIntoViewIfNeeded()
      await say(page, 'Release 1 paid; the rest wait for their condition', 2000)
    }
    await logout(page)

    // ---------------------------------------------------------------- 6. later releases
    if (c.release) {
      await login(page, c.release.who[0], c.release.who[1])
      await openApp(page, c.name)
      await openSection(page, 'Plan')
      await page.locator('[data-releases]').scrollIntoViewIfNeeded()
      await say(page, c.release.caption, 1800)
      await c.release.act(page)
      await expect(page.locator('[data-tranche="2"] [data-release-check="TR-02"]')).toHaveAttribute('data-result', 'PASS', { timeout: 20_000 })
      await logout(page)
      await login(page, 'ops1', 'Operations')
      await openApp(page, c.name)
      await openSection(page, 'Plan')
      await page.locator('[data-tranche="2"]').scrollIntoViewIfNeeded()
      await page.locator('[data-tranche="2"]').getByRole('button', { name: /^Release / }).click()
      await expect(page.getByRole('status')).toContainText('Release paid', { timeout: 30_000 })
      await expect(page.locator('[data-tranche="2"] [data-tranche-status]')).toHaveAttribute('data-tranche-status', 'PAID')
      await say(page, c.release.then, 2600)
      await logout(page)
      await login(page, 'sales1', 'Sales officer')
      await openApp(page, c.name)
      await openSection(page, 'Plan')
    }
    await say(page, c.end, 2800)
  })
}
