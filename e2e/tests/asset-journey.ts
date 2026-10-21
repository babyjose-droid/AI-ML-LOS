import { expect, test, type Page } from '@playwright/test'
import path from 'path'
import { openSection } from './sections'

/**
 * Shared recorded walkthrough for the asset-finance products (commercial vehicle, equipment, new car, rooftop solar,
 * medical equipment, consumer durable). One spec file per product calls assetJourney() so each gets its own video.
 * Borrowers, dealers and documents are fictional (SPECIMEN); the dealers are the demo masters seeded in V33.
 */
export interface AssetCase {
  title: string            // test title
  intro: string            // first caption
  product: string          // product code
  name: string; pan: string; mobile: string; dob: string; gender: 'MALE' | 'FEMALE'; city: string; pincode: string; address: string
  business: string; industry: string; vintage: string; income: string; expenses: string; account: string; ifsc: string
  amount: string; tenure: string; purpose: string; amountCaption: string
  kyc: [string, string][]   // [docType, fixture]
  dealer: string            // dealer name (as shown in the select)
  year: string
  prefix: string            // asset fixture prefix: <prefix>-quotation.pdf etc.
  insurance: boolean
  assetCaption: string      // what the AI read
  field: boolean            // product needs a field visit
  fieldNote?: string
  memo: boolean             // product has a credit memo stage
  post: [string, string][]  // [docType, fixture] due after disbursement
  lienAfter: string         // lien state after the post-disbursal documents
  end: string
}

export const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)
export const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)

export async function captions(page: Page) {
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
export async function say(page: Page, text: string, pause = 1800) {
  await page.evaluate(t => { sessionStorage.setItem('__cap', t); (window as unknown as { __cap?: () => void }).__cap?.() }, text)
  await page.waitForTimeout(pause)
}
export async function login(page: Page, user: string, who: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await say(page, `Signing in as ${who} (${user})`, 900)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}
export async function logout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
}
export async function openApp(page: Page, name: string) {
  await page.goto('/applications?view=list')
  await page.getByPlaceholder('Search name, PAN or application no.').fill(name)
  await page.getByRole('cell', { name, exact: true }).first().click()
  await expect(page.getByRole('heading', { name: new RegExp(name) })).toBeVisible()
}
const check = (page: Page, id: string) => page.locator(`[data-check="${id}"]`)
async function uploadAsset(page: Page, type: string, file: string) {
  await page.getByLabel('Document type').selectOption(type)
  await page.getByLabel('Asset document').setInputFiles(fixture(file))
  await page.locator('[data-asset]').getByRole('button', { name: 'Upload', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('uploaded and read', { timeout: 30_000 })
  await expect(page.locator('[data-asset] table').first().locator('tbody tr', { hasText: file })).toBeVisible()
}

export function assetJourney(c: AssetCase) {
  test.use({
    viewport: { width: 1366, height: 768 },
    video: { mode: 'on', size: { width: 1366, height: 768 } },
    launchOptions: { slowMo: 120 },
    actionTimeout: 30_000,
  })

  test(c.title, async ({ page }) => {
    test.setTimeout(15 * 60_000)
    await captions(page)

    // ---------------------------------------------------------------- 1. sales: the application and the borrower
    await login(page, 'sales1', 'Sales officer')
    await say(page, c.intro, 2200)
    await page.getByRole('link', { name: 'New application' }).click()
    await page.locator('select[name=productCode]').selectOption(c.product)
    await page.locator('input[name=loanAmount]').fill(c.amount)
    await page.locator('input[name=tenureMonths]').fill(c.tenure)
    await page.locator('input[name=purpose]').fill(c.purpose)
    await say(page, c.amountCaption, 1600)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=applicantName]').fill(c.name)
    await page.locator('input[name=pan]').fill(c.pan)
    await page.locator('input[name=mobile]').fill(c.mobile)
    await page.locator('input[name=dob]').fill(c.dob)
    await page.locator('select[name=gender]').selectOption(c.gender)
    await page.locator('input[name=city]').fill(c.city)
    await page.locator('input[name=pincode]').fill(c.pincode)
    await page.locator('input[name=address]').fill(c.address)
    await say(page, 'Borrower details as on PAN and Aadhaar', 1000)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=businessName]').fill(c.business)
    await page.locator('select[name=industry]').selectOption(c.industry)
    await page.locator('input[name=businessVintageYears]').fill(c.vintage)
    await page.locator('input[name=declaredMonthlyIncome]').fill(c.income)
    await page.locator('input[name=essentialExpenses]').fill(c.expenses)
    await say(page, 'Income and expenses for the affordability check', 1000)
    await page.getByRole('button', { name: 'Continue' }).click()
    await page.locator('input[name=bankAccountNo]').fill(c.account)
    await page.locator('input[name=bankIfsc]').fill(c.ifsc)
    for (const k of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${k}]`).check()
    await say(page, 'The borrower’s bank account (for the EMIs) and consents', 1000)
    await page.getByRole('button', { name: 'Continue' }).click()
    await expect(page.getByTestId('application-review')).toContainText(c.name)
    await page.getByRole('button', { name: 'Save and submit' }).click()
    await expect(page.getByRole('heading', { name: new RegExp(c.name) })).toBeVisible()
    await expect(state(page, 'APP')).toHaveText('Submitted')
    await expect(page.locator('.phases button[data-phase="4"]')).toContainText('Asset & dealer check')
    await say(page, 'Submitted. Stage 4 of this product is the asset and dealer check', 2000)

    await openSection(page, 'Documents')
    await say(page, 'Upload the borrower’s KYC (specimens)', 1000)
    for (const [t, f] of c.kyc) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('File').setInputFiles(fixture(f))
      await page.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible({ timeout: 30_000 })
    }

    // the asset: dealer, then the dealer's documents read by AI
    await openSection(page, 'Asset')
    await say(page, 'Asset section: choose the empanelled dealer or supplier and the year of manufacture', 1600)
    const dealerValue = await page.locator('select[name=dealerId] option', { hasText: c.dealer }).first().getAttribute('value')
    await page.locator('select[name=dealerId]').selectOption(dealerValue!)
    await page.locator('input[name=manufactureYear]').fill(c.year)
    await page.getByRole('button', { name: 'Save and check' }).click()
    await expect(page.getByRole('status')).toContainText('Asset saved', { timeout: 20_000 })
    await expect(check(page, 'AF-01')).toHaveAttribute('data-result', 'PASS')
    await say(page, 'Asset recorded. Now the dealer’s quotation, tax invoice and down-payment receipt', 1600)
    await uploadAsset(page, 'QUOTATION', `${c.prefix}-quotation.pdf`)
    await uploadAsset(page, 'INVOICE', `${c.prefix}-invoice.pdf`)
    await uploadAsset(page, 'DOWN_PAYMENT_RECEIPT', `${c.prefix}-receipt.pdf`)
    if (c.insurance) await uploadAsset(page, 'INSURANCE', `${c.prefix}-insurance.pdf`)
    await say(page, c.assetCaption, 2600)
    for (const id of ['AF-03', 'AF-04', 'AF-05', 'AF-06', 'AF-08', 'AF-09', 'AF-13']) await expect(check(page, id)).toHaveAttribute('data-result', 'PASS')
    await expect(check(page, 'AF-07')).toHaveAttribute('data-result', 'PENDING')
    await page.locator('[data-check="AF-13"]').scrollIntoViewIfNeeded()
    await say(page, 'Invoice adds up, GSTIN is the dealer’s, chassis/serial not financed before, within LTV and the quotation; AI reading matches', 2800)
    await say(page, 'Down payment is still to be verified by operations before disbursement', 1800)
    await logout(page)

    // ---------------------------------------------------------------- 2. operations: automated checks and the down payment
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await say(page, 'Step 2 · Operations runs the automated checks: documents, KYC, bank data, bureau, fraud', 1400)
    await page.locator('[data-action=process]').click()
    await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 60_000 })
    await expect(state(page, 'DOCS')).toHaveText('Complete')
    await expect(state(page, 'KYC')).toHaveText('Verified')
    await openSection(page, 'Asset')
    await say(page, 'Operations confirms the margin money with the dealer’s receipt', 1400)
    await page.getByRole('button', { name: 'Verify down payment' }).click()
    await expect(page.locator('[data-dp-verified]')).toBeVisible({ timeout: 20_000 })
    await expect(check(page, 'AF-07')).toHaveAttribute('data-result', 'PASS')
    await expect(page.locator('[data-asset-status]')).toHaveAttribute('data-asset-status', 'PASS')
    await say(page, 'All 13 asset checks pass. The asset stage of the workflow completes on its own', 2400)
    await logout(page)

    // ---------------------------------------------------------------- 3. field officer (products that need a visit)
    if (c.field) {
      await login(page, 'field1', 'Field officer')
      await openApp(page, c.name)
      await say(page, 'Step 3 · The field officer visits the business where the asset will work', 1400)
      await page.locator('[data-action=fieldVisit]').click()
      await page.locator('textarea[name=note]').fill(c.fieldNote ?? 'Business running; site seen; asset will be used here.')
      await page.getByRole('button', { name: 'Confirm' }).click()
      await expect(state(page, 'FIELD')).toHaveText('Done')
      await say(page, 'Field visit recorded', 1000)
      await logout(page)
    }

    // ---------------------------------------------------------------- 4. credit: decision and memo
    await login(page, 'co1', 'Credit officer')
    await openApp(page, c.name)
    await say(page, `Step ${c.field ? 4 : 3} · Credit reviews the decision${c.memo ? ' and the credit memo' : ''}`, 1400)
    const run = page.locator('[data-action=runDecision]')
    if ((await state(page, 'DECISION').innerText()).trim() === 'Pending' && await run.isVisible()) await run.click()
    await expect(state(page, 'DECISION')).not.toHaveText('Pending', { timeout: 30_000 })
    await openSection(page, 'Decision')
    await expect(page.getByText('What drove the risk')).toBeVisible()
    await say(page, 'Decision engine: probability of default, band, affordability and every policy rule', 2400)
    if (c.memo) {
      await openSection(page, 'Credit memo')
      await say(page, 'AI-drafted credit memo, with the asset, dealer and loan-to-cost', 1600)
      const sign = page.locator('[data-action=signMemo]')
      if (await sign.isVisible()) { await sign.click(); await say(page, 'Credit memo signed and locked', 1000) }
    }
    await logout(page)

    // ---------------------------------------------------------------- 5. sanction (unless the product auto-sanctioned it)
    // delegation: L1 up to ₹2 lakh, L2 up to ₹10 lakh (credit manager), above that L3 (CRO)
    const l3 = Number(c.amount) > 1_000_000
    await login(page, l3 ? 'cro1' : 'cm1', l3 ? 'CRO' : 'Credit manager')
    await openApp(page, c.name)
    const s = (await state(page, 'SANCTION').innerText()).trim()
    if (s.startsWith('Pending')) {
      await say(page, `Sanction by the authority the delegation matrix picks (${s}); the asset rules must be clear first`, 1800)
      await page.locator('[data-action=sanction]').click()
      await page.locator('textarea[name=note]').fill('Asset checks PASS, down payment verified, within loan-to-cost; sanction as recommended')
      await page.getByRole('button', { name: 'Confirm' }).click()
    } else {
      await say(page, `Small ticket: sanctioned automatically within the product's limit (${s})`, 2000)
    }
    await expect(state(page, 'SANCTION')).toHaveText('Sanctioned', { timeout: 20_000 })
    await openSection(page, 'Sanction & KFS')
    await expect(page.getByText('APR (all-in)')).toBeVisible()
    await say(page, 'Sanction letter and KFS: conditions include payment to the dealer and the post-disbursal documents', 2400)
    await logout(page)

    // ---------------------------------------------------------------- 6. borrower accepts the KFS
    await login(page, 'sales1', 'Sales officer')
    await openApp(page, c.name)
    await say(page, 'The borrower accepts the KFS with an OTP', 1200)
    await page.locator('[data-action=acceptKfs]').click()
    await page.locator('input[name=otp]').fill('123456')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
    await logout(page)

    // ---------------------------------------------------------------- 7. operations: documents, payout to the dealer, post-disbursal documents
    await login(page, 'ops1', 'Operations')
    await openApp(page, c.name)
    await openSection(page, 'Signing pack')
    await say(page, 'Loan documents, including the hypothecation deed with the asset’s details', 1400)
    const make = page.locator('[data-action^=make-]:not([disabled])').first()
    if (await make.isVisible()) { await make.click(); await page.waitForTimeout(2500) }
    await page.locator('[data-action=disburse]').click()
    await expect(page.getByRole('status')).toContainText('UTR', { timeout: 30_000 })
    await expect(state(page, 'APP')).toHaveText('Disbursed')
    await say(page, `Disbursed to ${c.dealer}, not to the borrower: penny-drop on the dealer’s account, payout and UTR`, 2600)
    await openSection(page, 'Asset')
    await expect(page.locator('[data-post-docs]')).toBeVisible()
    await say(page, 'Documents now due from the dealer, with a due date; they show in My work until collected', 2200)
    for (const [t, f] of c.post) {
      await page.getByLabel('Document type').selectOption(t)
      await page.getByLabel('Asset document').setInputFiles(fixture(f))
      await page.locator('[data-asset]').getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator(`[data-post-doc="${t}"] .pill`)).toHaveText('received', { timeout: 30_000 })
    }
    await expect(page.locator('.kstrip')).toContainText(c.lienAfter)
    await say(page, c.end, 2600)

    await page.goto('/asset-finance')
    await expect(page.getByRole('heading', { name: 'Asset finance' })).toBeVisible()
    await say(page, 'Asset finance book: loans by asset class, dealer exposure and documents still due', 2600)
  })
}
