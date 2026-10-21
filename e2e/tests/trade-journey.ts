import { expect, test, type Page } from '@playwright/test'
import { captions, fixture, login, logout, openApp, say, state } from './asset-journey'
import { day, tradeInvoice } from './pdf'
import { openSection } from './sections'

/**
 * Shared recorded walkthrough for the trade-finance products: invoice & receivables (INV-FIN), supply chain / vendor (SCF),
 * dealer & distributor (DDF). A revolving limit is set up, sanctioned and activated; then an invoice is drawn, confirmed,
 * funded (maker-checker) and repaid. Borrowers, anchors and buyers are fictional; invoices are built at run time (SPECIMEN).
 */
export interface TradeCase {
  title: string; intro: string; product: string
  name: string; pan: string; mobile: string; dob: string; city: string; pincode: string; address: string
  business: string; industry: string; vintage: string; income: string; expenses: string; account: string; ifsc: string
  limit: string; purpose: string; limitCaption: string
  kyc: [string, string][]
  gstin: string
  anchor?: string                    // programme products
  sublimits?: [string, string][]     // receivables: [buyer code, amount]
  structureCaption: string
  invoice: Parameters<typeof tradeInvoice>[0] & { file: string }
  readCaption: string
  confirmBy: string; confirmRef: string
  fundCaption: string
  duplicate?: boolean                // show the same invoice offered twice
  repayCaption: string
  end: string
}

const check = (page: Page, id: string) => page.locator(`[data-invoice-panel] [data-check="${id}"]`)

async function openDrawdown(page: Page, nth = 0) {
  const ref = page.locator('[data-trade] [data-invoice]').nth(nth)
  const no = (await ref.innerText()).trim()
  if (!(await page.locator(`[data-invoice-panel="${no}"]`).isVisible())) await ref.click()
  await expect(page.locator(`[data-invoice-panel="${no}"]`)).toBeVisible()
  return no
}

export function tradeJourney(c: TradeCase) {
  test.use({
    viewport: { width: 1366, height: 768 },
    video: { mode: 'on', size: { width: 1366, height: 768 } },
    launchOptions: { slowMo: 120 },
    actionTimeout: 30_000,
  })

  test(c.title, async ({ page }) => {
    test.setTimeout(15 * 60_000)
    await captions(page)

    // ---------------------------------------------------------------- 1. sales: application for the limit
    await login(page, 'sales1', 'Sales officer')
    await say(page, c.intro, 2200)
    await page.getByRole('link', { name: 'New application' }).click()
    await page.locator('select[name=productCode]').selectOption(c.product)
    await page.locator('input[name=loanAmount]').fill(c.limit)
    await page.locator('input[name=tenureMonths]').fill('12')
    await page.locator('input[name=purpose]').fill(c.purpose)
    await say(page, c.limitCaption, 1800)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=applicantName]').fill(c.name)
    await page.locator('input[name=pan]').fill(c.pan)
    await page.locator('input[name=mobile]').fill(c.mobile)
    await page.locator('input[name=dob]').fill(c.dob)
    await page.locator('select[name=gender]').selectOption('MALE')
    await page.locator('input[name=city]').fill(c.city)
    await page.locator('input[name=pincode]').fill(c.pincode)
    await page.locator('input[name=address]').fill(c.address)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=businessName]').fill(c.business)
    await page.locator('select[name=industry]').selectOption(c.industry)
    await page.locator('input[name=businessVintageYears]').fill(c.vintage)
    await page.locator('input[name=declaredMonthlyIncome]').fill(c.income)
    await page.locator('input[name=essentialExpenses]').fill(c.expenses)
    await say(page, 'Business, years in business and monthly turnover', 1000)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=bankAccountNo]').fill(c.account)
    await page.locator('input[name=bankIfsc]').fill(c.ifsc)
    for (const k of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${k}]`).check()
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByTestId('application-review')).toContainText(c.name)
    await page.getByRole('button', { name: 'Save and submit' }).click()
    await expect(page.getByRole('heading', { name: new RegExp(c.name) })).toBeVisible()
    await expect(state(page, 'APP')).toHaveText('Submitted')
    await expect(page.locator('.phases button[data-phase="4"]')).toContainText('Programme & limit')
    await expect(page.locator('.phases button[data-phase="8"]')).toContainText('Limit activation')
    await say(page, 'Submitted. Stage 4 sets the programme and limit; stage 8 activates the limit instead of paying a loan', 2200)

    await openSection(page, 'Documents')
    for (const [t, f] of c.kyc) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('File').setInputFiles(fixture(f))
      await page.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
    }
    await say(page, 'KYC and Udyam registration uploaded (specimens)', 1000)

    await openSection(page, 'Limit')
    await page.locator('input[name=borrowerGstin]').fill(c.gstin)
    if (c.anchor) {
      const v = await page.locator('select[name=anchorId] option', { hasText: c.anchor }).first().getAttribute('value')
      await page.locator('select[name=anchorId]').selectOption(v!)
    }
    for (const [code, amt] of c.sublimits ?? []) await page.locator(`input[name="sub-${code}"]`).fill(amt)
    await say(page, c.structureCaption, 2400)
    await page.getByRole('button', { name: 'Save and check' }).click()
    await expect(page.getByRole('status')).toContainText('Limit structure saved', { timeout: 20_000 })
    await expect(page.locator('[data-structure="LS-04"]')).toHaveAttribute('data-result', 'PASS')
    await expect(page.locator('[data-structure]').filter({ has: page.locator('.pill.bad') })).toHaveCount(0)
    await say(page, 'Limit checks pass: programme or buyers approved, headroom, and the GSTIN is on the borrower’s PAN', 2400)
    await logout(page)

    // ---------------------------------------------------------------- 2. operations: automated checks
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank data, GST, bureau, fraud', 1400)
    await page.locator('[data-action=process]').click()
    await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
    await expect(state(page, 'DOCS')).toHaveText('Complete')
    await expect(state(page, 'KYC')).toHaveText('Verified')
    await logout(page)

    // ---------------------------------------------------------------- 3. credit: decision (revolving) and memo
    await login(page, 'co1', 'Credit officer')
    await openApp(page, c.name)
    const run = page.locator('[data-action=runDecision]')
    if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
    await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
    await openSection(page, 'Decision')
    await expect(page.getByText('Affordability (revolving limit)')).toBeVisible()
    await say(page, 'Step 3 · The decision treats this as a revolving limit: interest at full use, and the limit within 25% of annual credits', 3000)
    await openSection(page, 'Credit memo')
    const sign = page.locator('[data-action=signMemo]')
    if (await sign.isVisible()) { await sign.click(); await say(page, 'Credit memo signed', 1000) }
    await logout(page)

    // ---------------------------------------------------------------- 4. sanction, 5. KFS
    await login(page, 'cm1', 'Credit manager')
    await openApp(page, c.name)
    await say(page, 'Step 4 · The credit manager sanctions the limit (up to ₹10 lakh is L2)', 1400)
    await page.locator('[data-action=sanction]').click()
    await page.locator('textarea[name=note]').fill('Limit within turnover norm; receivables from approved counterparties; sanction as recommended')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
    await openSection(page, 'Sanction & KFS')
    await expect(page.getByText('APR (all-in)')).toBeVisible()
    await say(page, 'KFS for a revolving limit: interest monthly, principal on each invoice’s due date', 2400)
    await logout(page)
    await login(page, 'sales1', 'Sales officer')
    await openApp(page, c.name)
    await page.locator('[data-action=acceptKfs]').click()
    await page.locator('input[name=otp]').fill('123456')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
    await say(page, 'The borrower accepts the KFS by OTP', 1000)
    await logout(page)

    // ---------------------------------------------------------------- 6. operations: activate the limit
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Signing pack')
    const make = page.locator('[data-action^=make-]:not([disabled])').first()
    if (await make.isVisible()) { await make.click(); await page.waitForTimeout(2500) }
    await say(page, 'Step 5 · Operations activates the limit: the borrower’s account is verified, no money moves yet', 1600)
    await expect(page.locator('[data-action=disburse]')).toHaveText('Activate limit')
    await page.locator('[data-action=disburse]').click()
    await expect(page.getByRole('status')).toContainText('activated', { timeout: 30_000 })
    await openSection(page, 'Limit')
    await expect(page.locator('[data-limit-status]')).toHaveAttribute('data-limit-status', 'ACTIVE')
    await say(page, 'Limit active for 12 months, fully available', 1800)
    await logout(page)

    // ---------------------------------------------------------------- 7. sales: draw against an invoice
    await login(page, 'sales1', 'Sales officer')
    await openApp(page, c.name)
    await openSection(page, 'Limit')
    await say(page, 'Step 6 · An invoice is offered for financing', 1400)
    const pdf = tradeInvoice(c.invoice)
    await page.getByLabel('Invoice file').setInputFiles({ name: c.invoice.file, mimeType: 'application/pdf', buffer: pdf })
    await page.getByRole('button', { name: 'Submit invoice' }).click()
    await expect(page.getByRole('status')).toContainText('Invoice submitted', { timeout: 30_000 })
    await openDrawdown(page)
    await say(page, c.readCaption, 2800)
    await expect(check(page, 'TF-10')).toHaveAttribute('data-result', 'PENDING')
    for (const id of ['TF-01', 'TF-02', 'TF-03', 'TF-04', 'TF-05', 'TF-06', 'TF-07', 'TF-08', 'TF-09', 'TF-11', 'TF-12'])
      await expect(check(page, id)).toHaveAttribute('data-result', 'PASS')
    await say(page, `All checks pass except one: the invoice must be confirmed by ${c.confirmBy}`, 2400)
    await logout(page)

    // ---------------------------------------------------------------- 8. operations: confirmation and funding (maker-checker)
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Limit')
    const ref = await openDrawdown(page)
    await page.getByLabel('Confirmation reference').fill(c.confirmRef)
    await say(page, `Step 7 · Operations records ${c.confirmBy}'s acceptance of the invoice`, 1600)
    await page.getByRole('button', { name: 'Record confirmation' }).click()
    await expect(page.getByRole('status')).toContainText('Confirmation recorded')
    await openDrawdown(page)
    await expect(check(page, 'TF-10')).toHaveAttribute('data-result', 'PASS')
    await page.locator(`[data-invoice-panel="${ref}"]`).getByRole('button', { name: /^Fund / }).click()
    await expect(page.getByRole('status')).toContainText('Drawdown funded', { timeout: 30_000 })
    await expect(page.locator(`[data-invoice-status="${ref}"]`)).toHaveAttribute('data-status', 'FUNDED')
    await openDrawdown(page)
    await expect(page.locator('[data-funded]')).toContainText('UTR')
    await say(page, c.fundCaption, 2800)
    await logout(page)

    if (c.duplicate) {
      // the same invoice offered again is stopped
      await login(page, 'sales1', 'Sales officer')
      await openApp(page, c.name)
      await openSection(page, 'Limit')
      await say(page, 'What if the same invoice is offered again?', 1400)
      await page.getByLabel('Invoice file').setInputFiles({ name: 'resubmitted-' + c.invoice.file, mimeType: 'application/pdf', buffer: pdf })
      await page.getByRole('button', { name: 'Submit invoice' }).click()
      await expect(page.getByRole('status')).toContainText('Invoice submitted', { timeout: 30_000 })
      const dup = await openDrawdown(page, 0)
      await expect(check(page, 'TF-03')).toHaveAttribute('data-result', 'FAIL')
      await say(page, `TF-03 fails: the invoice number and the file are already on ${ref}. This check cannot be overridden`, 2800)
      await logout(page)
      await login(page, 'ops1', 'Operations')
      await openApp(page, c.name)
      await openSection(page, 'Limit')
      await openDrawdown(page, 0)
      await page.getByLabel('Reject reason').fill('Duplicate of an invoice already financed')
      await page.getByRole('button', { name: 'Reject' }).click()
      await expect(page.locator(`[data-invoice-status="${dup}"]`)).toHaveAttribute('data-status', 'REJECTED')
      await say(page, 'Operations rejects the duplicate', 1400)
    } else {
      await login(page, 'ops1', 'Operations')
      await openApp(page, c.name)
      await openSection(page, 'Limit')
    }

    // ---------------------------------------------------------------- 9. repayment on the due date
    const funded = page.locator('[data-trade] [data-invoice]', { hasText: ref })
    await funded.click()
    await expect(page.locator(`[data-invoice-panel="${ref}"]`)).toBeVisible()
    await say(page, c.repayCaption, 2000)
    await page.getByLabel('Repayment reference').fill('UTR' + c.pan.slice(5, 9) + '0917')
    await page.getByRole('button', { name: 'Record repayment' }).click()
    await expect(page.getByRole('status')).toContainText('Repayment recorded')
    await expect(page.locator(`[data-invoice-status="${ref}"]`)).toHaveAttribute('data-status', 'REPAID')
    await expect(page.locator('[data-available]')).toHaveAttribute('data-available', c.limit)
    await say(page, 'Repaid with interest: the limit is fully available again for the next invoice', 2400)

    await page.goto('/trade')
    await expect(page.getByRole('heading', { name: 'Trade finance' })).toBeVisible()
    await say(page, 'Trade finance book: limits, utilisation, the invoice queue, anchors and buyers', 2200)
    await page.getByRole('tab', { name: 'Anchors & buyers' }).click()
    await say(page, c.end, 2600)
  })
}
