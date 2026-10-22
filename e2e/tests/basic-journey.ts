import { expect, test } from '@playwright/test'
import { captions, fixture, login, logout, openApp, say, state } from './asset-journey'
import { openSection } from './sections'

/**
 * Recorded walkthrough for products configured on the standard business-loan flow (merchant, micro-enterprise working capital,
 * doctor & professional, franchise, school & hospital): application, KYC, automated checks, field visit where the product needs
 * one, decision and memo, sanction by the delegated authority, KFS and disbursement. Fictional borrowers, SPECIMEN documents.
 */
export interface BasicCase {
  title: string; intro: string; product: string
  name: string; pan: string; mobile: string; dob: string; gender: 'MALE' | 'FEMALE'; city: string; pincode: string; address: string
  business: string; industry: string; vintage: string; income: string; expenses: string; account: string; ifsc: string
  amount: string; tenure: string; purpose: string; amountCaption: string
  kyc: [string, string][]
  fieldNote?: string
  decisionCaption: string
  kfsCaption: string
  end: string
}

export function basicJourney(c: BasicCase) {
  test.use({ viewport: { width: 1366, height: 768 }, video: { mode: 'on', size: { width: 1366, height: 768 } }, launchOptions: { slowMo: 120 }, actionTimeout: 30_000 })

  test(c.title, async ({ page }) => {
    test.setTimeout(12 * 60_000)
    await captions(page)
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
    await say(page, 'Business, years running and income: the industry picks the scorecard', 1200)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=bankAccountNo]').fill(c.account)
    await page.locator('input[name=bankIfsc]').fill(c.ifsc)
    for (const k of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${k}]`).check()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByTestId('application-review')).toContainText(c.name)
    await page.getByRole('button', { name: 'Save and submit' }).click()
    await expect(page.getByRole('heading', { name: new RegExp(c.name) })).toBeVisible()
    await expect(state(page, 'APP')).toHaveText('Submitted')
    await openSection(page, 'Documents')
    for (const [t, f] of c.kyc) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('File').setInputFiles(fixture(f))
      await page.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
    }
    await say(page, 'KYC uploaded (specimens); Document AI reads them during the checks', 1400)
    await logout(page)

    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await say(page, 'Operations runs the automated checks: documents, KYC, bank data (Account Aggregator), bureau, GST, fraud', 1400)
    await page.locator('[data-action=process]').click()
    await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
    await expect(state(page, 'DOCS')).toHaveText('Complete')
    await expect(state(page, 'KYC')).toHaveText('Verified')
    await logout(page)

    if (c.fieldNote) {
      await login(page, 'field1', 'Field officer')
      await openApp(page, c.name)
      await say(page, 'The product needs a field visit at this amount: the field officer visits the business', 1600)
      await page.locator('[data-action=fieldVisit]').click()
      await page.locator('textarea[name=note]').fill(c.fieldNote)
      await page.getByRole('button', { name: 'Confirm' }).click()
      await expect(state(page, 'FIELD')).toHaveText('Done')
      await logout(page)
    }

    await login(page, 'co1', 'Credit officer')
    await openApp(page, c.name)
    const run = page.locator('[data-action=runDecision]')
    if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
    await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
    await openSection(page, 'Decision')
    await expect(page.getByText('What drove the risk')).toBeVisible()
    await say(page, c.decisionCaption, 2600)
    await openSection(page, 'Credit memo')
    const sign = page.locator('[data-action=signMemo]')
    if (await sign.isVisible()) { await sign.click(); await say(page, 'Credit memo signed', 900) }
    await logout(page)

    const l3 = Number(c.amount) > 1_000_000
    await login(page, l3 ? 'cro1' : 'cm1', l3 ? 'CRO' : 'Credit manager')
    await openApp(page, c.name)
    const s = (await state(page, 'SANCTION').innerText()).trim()
    if (s.startsWith('Pending')) {
      await say(page, `Sanction by the ${l3 ? 'CRO (above ₹10 lakh)' : 'credit manager'}`, 1400)
      await page.locator('[data-action=sanction]').click()
      await page.locator('textarea[name=note]').fill('Cash flows and bureau support the recommended amount; sanction as recommended')
      await page.getByRole('button', { name: 'Confirm' }).click()
    }
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
    await openSection(page, 'Sanction & KFS')
    await expect(page.getByText('APR (all-in)')).toBeVisible()
    await say(page, c.kfsCaption, 2400)
    await logout(page)

    await login(page, 'sales1', 'Sales officer')
    await openApp(page, c.name)
    await page.locator('[data-action=acceptKfs]').click()
    await page.locator('input[name=otp]').fill('123456')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
    await say(page, 'The borrower accepts the KFS by OTP', 1000)
    await logout(page)

    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Signing pack')
    const make = page.locator('[data-action^=make-]:not([disabled])').first()
    if (await make.isVisible()) { await make.click(); await page.waitForTimeout(2500) }
    await page.locator('[data-action=disburse]').click()
    await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
    await expect(state(page, 'APP')).toHaveText('Disbursed')
    await say(page, c.end, 2800)
  })
}
