import { expect, test, type Page } from '@playwright/test'
import { captions, fixture, login, logout, openApp, say, state } from './asset-journey'
import { makePdf } from './pdf'
import { openSection } from './sections'

/**
 * Recorded walkthrough for loans against securities and fixed deposits: the pledge read from the CAS statement or the deposit
 * receipt, drawing power, sanction, lien before disbursement, and (for securities) a margin call after a price fall, cured by
 * pledging more. Fictional borrowers, securities and deposits; documents built at run time (SPECIMEN).
 */
export interface PledgeCase {
  title: string; intro: string; product: string
  name: string; pan: string; mobile: string; dob: string; gender: 'MALE' | 'FEMALE'; city: string; pincode: string; address: string
  business: string; vintage: string; income: string; expenses: string; account: string; ifsc: string
  amount: string; tenure: string; purpose: string; amountCaption: string
  kyc: [string, string][]
  doc: { type: string; file: string; title: string; issuer: string; lines: string[] }
  readCaption: string
  checksCaption: string
  kfsCaption: string
  lien: { how: 'ref'; ref: string; caption: string } | { how: 'doc'; doc: { file: string; title: string; issuer: string; lines: string[] }; caption: string }
  disbCaption: string
  margin?: { prices: string; dropCaption: string; isin: string; quantity: string; ref: string; cureCaption: string }
  end: string
}

const pdf = (d: { file: string; title: string; issuer: string; lines: string[] }) => ({ name: d.file, mimeType: 'application/pdf', buffer: makePdf(d.title, d.lines, d.issuer) })

export function pledgeJourney(c: PledgeCase) {
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
    await page.locator('select[name=industry]').selectOption('SALARIED')
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
    await openSection(page, 'Documents')
    for (const [t, f] of c.kyc) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('File').setInputFiles(fixture(f))
      await page.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
    }
    await openSection(page, 'Pledge')
    await page.getByLabel('Pledge document type').selectOption(c.doc.type)
    await page.getByLabel('Pledge document', { exact: true }).setInputFiles(pdf(c.doc))
    await page.locator('[data-pledge]').getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('uploaded and read', { timeout: 30_000 })
    await say(page, c.readCaption, 2800)
    await page.locator('[data-pledge-check]').first().scrollIntoViewIfNeeded()
    await expect(page.locator('[data-pledge-check$="-03"]')).toHaveAttribute('data-result', 'PASS')
    await say(page, c.checksCaption, 2800)
    await logout(page)

    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await say(page, 'Operations runs the automated checks: documents, KYC, bank data, bureau, fraud', 1200)
    await page.locator('[data-action=process]').click()
    await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
    await expect(state(page, 'KYC')).toHaveText('Verified')
    await logout(page)

    await login(page, 'co1', 'Credit officer')
    await openApp(page, c.name)
    const run = page.locator('[data-action=runDecision]')
    if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
    await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
    await openSection(page, 'Decision')
    await expect(page.getByText('Affordability (interest only, principal from the pledge)')).toBeVisible()
    await say(page, 'The decision assesses only the monthly interest: the principal comes from the pledge', 2400)
    await logout(page)

    await login(page, 'cm1', 'Credit manager')
    await openApp(page, c.name)
    const s = (await state(page, 'SANCTION').innerText()).trim()
    if (s.startsWith('Pending')) {
      await page.locator('[data-action=sanction]').click()
      await page.locator('textarea[name=note]').fill('Drawing power covers the loan; interest well within income; sanction as recommended')
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
    await logout(page)

    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Pledge')
    await say(page, c.lien.caption, 1800)
    if (c.lien.how === 'ref') {
      await page.getByLabel('Lien reference').fill(c.lien.ref)
      await page.getByRole('button', { name: 'Mark lien' }).click()
    } else {
      await page.getByLabel('Pledge document type').selectOption('LIEN_CONFIRMATION')
      await page.getByLabel('Pledge document', { exact: true }).setInputFiles(pdf(c.lien.doc))
      await page.locator('[data-pledge]').getByRole('button', { name: 'Upload', exact: true }).click()
    }
    await expect(page.locator('[data-lien]')).toHaveAttribute('data-lien', 'MARKED', { timeout: 20_000 })
    await openSection(page, 'Signing pack')
    const make = page.locator('[data-action^=make-]:not([disabled])').first()
    if (await make.isVisible()) { await make.click(); await page.waitForTimeout(2500) }
    await page.locator('[data-action=disburse]').click()
    await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
    await expect(state(page, 'APP')).toHaveText('Disbursed')
    await say(page, c.disbCaption, 2400)

    if (c.margin) {
      await page.goto('/pledges?tab=securities')
      await expect(page.getByRole('heading', { name: 'Pledges & margin' })).toBeVisible()
      await page.getByLabel('Price file').fill(c.margin.prices)
      await say(page, 'Next day: the price file comes in after a market fall', 1600)
      await page.getByRole('button', { name: 'Load prices and revalue' }).click()
      await expect(page.locator('[data-revaluation]')).toContainText('1 margin call(s) raised')
      await page.getByRole('tab', { name: 'Margin calls' }).click()
      await say(page, c.margin.dropCaption, 2600)
      await openApp(page, c.name)
      await openSection(page, 'Pledge')
      await page.locator('[data-margin]').scrollIntoViewIfNeeded()
      const v = await page.locator('select[aria-label="Security to pledge"] option', { hasText: c.margin.isin }).first().getAttribute('value')
      await page.getByLabel('Security to pledge').selectOption(v!)
      await page.getByLabel('Quantity to pledge').fill(c.margin.quantity)
      await page.getByLabel('Top-up pledge reference').fill(c.margin.ref)
      await page.getByRole('button', { name: 'Add to pledge' }).click()
      await expect(page.locator('[data-call-status]').first()).toHaveAttribute('data-call-status', 'CURED', { timeout: 20_000 })
      await say(page, c.margin.cureCaption, 2600)
      await page.goto('/pledges')
    }
    await say(page, c.end, 2600)
  })
}
