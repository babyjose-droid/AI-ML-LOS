import { expect, test, type Page } from '@playwright/test'
import { fixture, say } from './asset-journey'

/**
 * Recorded walkthrough of the mobile app (the React Native screens in a phone-sized browser): every role works the case on
 * a phone. The sales officer captures a merchant loan at the shop with no signal (application, KYC photos, submission are
 * queued and sync when the signal returns); operations runs the checks; the field officer visits offline (GPS check-in,
 * checklist, camera photos) and the report syncs later; credit runs the decision and signs the memo; the credit manager
 * sanctions; the borrower accepts the KFS on the sales officer's phone; operations disburses.
 * Fictional borrower and SPECIMEN documents.
 */
const MOBILE = process.env.MOBILE_URL ?? 'http://localhost:3002'
const SHOP = { latitude: 20.00731, longitude: 73.79102 }   // Panchavati, Nashik (fictional shop)

test.use({
  baseURL: MOBILE,
  viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2,
  video: { mode: 'on', size: { width: 390, height: 844 } }, launchOptions: { slowMo: 120 }, actionTimeout: 30_000,
  permissions: ['geolocation', 'camera'], geolocation: { ...SHOP, accuracy: 12 },
})

async function captions(page: Page) {
  await page.addInitScript(() => {
    const draw = () => {
      let el = document.getElementById('__cap')
      if (!el) {
        el = document.createElement('div')
        el.id = '__cap'
        el.setAttribute('style', 'position:fixed;left:8px;right:8px;bottom:70px;z-index:99999;padding:8px 12px;border-radius:10px;background:rgba(20,23,31,.9);color:#fff;font:600 13px/1.35 system-ui,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.25);pointer-events:none;text-align:center')
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

async function login(page: Page, user: string, who: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await say(page, `On the phone: ${who} (${user}) signs in`, 900)
  await page.getByTestId('sign-in').click()
  await expect(page.getByRole('tablist')).toBeVisible()
}
async function logout(page: Page) {
  await page.getByTestId('tab-me').click()
  await page.getByTestId('sign-out').click()
  await expect(page.getByTestId('sign-in')).toBeVisible()
}
async function openApp(page: Page, name: string) {
  await page.getByTestId('tab-apps').click()
  await page.getByTestId('search').fill(name)
  await page.getByTestId('search').press('Enter')
  await page.getByRole('button', { name: new RegExp('^' + name) }).first().click()
  await expect(page.getByTestId('app-detail')).toBeVisible()
}
async function pick(page: Page, testId: string, file: string) {
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId(testId).click()
  await (await chooser).setFiles(fixture(file))
}
const radio = (page: Page, name: string) => page.getByRole('radio', { name, exact: true }).first().click()

test('Mobile app journey (recorded): sales offline capture, operations checks, offline field visit, credit, sanction, KFS and disbursement on the phone', async ({ page, context }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)
  const name = 'Sunil Kale'

  // ---- sales officer at the shop ----
  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Rhythm mobile: one app for sales, field, credit and operations; each role sees its own tabs and actions', 2400)
  await page.getByTestId('tab-new').click()
  await page.getByTestId('f-product').click()
  await page.getByTestId('f-product-search').fill('Merchant')
  await page.getByRole('button', { name: 'Merchant & retailer loan' }).click()
  await page.getByTestId('f-loanAmount').fill('400000')
  await page.getByTestId('f-tenureMonths').fill('24')
  await page.getByTestId('f-purpose').fill('Stock for the wedding season')
  await say(page, 'New merchant loan of ₹4 lakh for 24 months, captured at the shop', 1400)
  await page.getByTestId('next').click()
  await page.getByTestId('f-applicantName').fill(name)
  await page.getByTestId('f-pan').fill('SUNPK5384K')
  await page.getByTestId('f-mobile').fill('9876531277')
  await page.getByTestId('f-dob').fill('1986-07-21')
  await radio(page, 'Male')
  await page.getByTestId('f-address').fill('Shop 9, Panchavati Karanja')
  await page.getByTestId('f-city').fill('Nashik')
  await page.getByTestId('f-pincode').fill('422003')
  await page.getByTestId('locate').click()
  await expect(page.getByTestId('locate')).toContainText('Location saved')
  await say(page, "The shop's GPS location is saved: the field visit is checked against it", 1600)
  await page.getByTestId('next').click()
  await page.getByTestId('f-businessName').fill('Kale General Stores')
  await radio(page, 'Kirana')
  await page.getByTestId('f-businessVintageYears').fill('9')
  await page.getByTestId('f-declaredMonthlyIncome').fill('85000')
  await page.getByTestId('f-essentialExpenses').fill('28000')
  await page.getByTestId('next').click()
  await page.getByTestId('f-bankAccountNo').fill('50200977112233')
  await page.getByTestId('f-bankIfsc').fill('HDFC0001234')
  for (const c of ['KYC', 'Credit bureau', 'Account Aggregator']) await page.getByRole('checkbox', { name: new RegExp('^' + c) }).click()
  await say(page, 'Consent for KYC, bureau and Account Aggregator, recorded on the phone', 1200)
  await page.getByTestId('next').click()
  for (const [t, f] of [['PAN', 'mob-sunil-pan.png'], ['Aadhaar', 'mob-sunil-aadhaar.png'], ['Udyam', 'mob-sunil-udyam.png']]) {
    await radio(page, t)
    await pick(page, 'new-doc-file', f)
    await expect(page.getByText(f)).toBeVisible()
  }
  await say(page, 'KYC documents photographed or picked on the phone (specimens); Aadhaar is masked to the last 4 digits on the server', 1800)
  await page.getByTestId('next').click()
  await context.setOffline(true)
  await expect(page.getByTestId('offline-bar')).toBeVisible()
  await say(page, 'No signal in the market: the app keeps working offline', 1600)
  await page.getByTestId('save-submit').click()
  await expect(page.getByTestId('ok')).toContainText('Saved on this phone')
  await expect(page.getByTestId('outbox-badge')).toHaveText('5')
  await page.getByTestId('tab-outbox').click()
  await expect(page.getByTestId('job-application')).toBeVisible()
  await say(page, 'Outbox: the application, three documents and the submission wait on the phone, in order', 2400)
  await context.setOffline(false)
  await say(page, 'Signal is back: the outbox sends everything by itself', 1200)
  await expect(page.getByTestId('outbox-waiting')).toContainText('0', { timeout: 60_000 })
  await expect(page.getByTestId('outbox-badge')).toHaveCount(0)
  await openApp(page, name)
  await expect(page.getByTestId('app-state')).toContainText('Submitted')
  await say(page, 'Created on the server, documents uploaded and submitted', 1600)
  await logout(page)

  // ---- operations ----
  await login(page, 'ops1', 'Operations')
  await openApp(page, name)
  await say(page, 'Operations runs the automated checks from the phone: documents, KYC, bank data, bureau, GST, fraud', 1600)
  await page.getByTestId('act-process').click()
  await expect(page.getByTestId('ok')).toContainText('Decision engine', { timeout: 90_000 })
  await logout(page)

  // ---- field officer, offline at the shop ----
  await login(page, 'field1', 'Field officer')
  const card = page.locator('[data-testid^="visit-"]', { hasText: name })
  await expect(card).toBeVisible()
  await say(page, 'The visit list is kept on the phone, with call and map buttons', 1600)
  await context.setOffline(true)
  await card.getByRole('button', { name: 'Start visit' }).click()
  await say(page, 'Offline at the shop: check in with GPS', 1200)
  await page.getByTestId('checkin').click()
  await expect(page.getByTestId('checked-in')).toContainText('m from the business')
  for (const k of ['businessRunning', 'ownerMet', 'matchesType']) await page.getByTestId('q-' + k).getByRole('radio', { name: 'Yes', exact: true }).click()
  await page.getByTestId('q-premises').getByRole('radio', { name: 'Rented', exact: true }).click()
  await page.getByTestId('q-stockValue').fill('160000')
  await page.getByTestId('q-shopSizeSqft').fill('240')
  await radio(page, 'High')
  for (const k of ['front', 'stock', 'owner']) await pick(page, 'photo-' + k, `mob-visit-${k}.jpg`)
  await say(page, 'Kirana checklist and camera photos, each stamped with time and place', 1800)
  await page.getByLabel('Reference 1: name').fill('Prakash Wagh (wholesaler)')
  await page.getByLabel('Reference 1: phone').fill('9822001122')
  await page.getByLabel('Reference 1: what they said').fill('Buys weekly, pays on time')
  await page.getByTestId('remarks').fill('Shop open and busy, good stock of groceries and FMCG, UPI QR in use; owner present.')
  await page.getByTestId('save-visit').click()
  await expect(page.getByTestId('toast')).toContainText('saved on this phone')
  await expect(page.getByTestId('outbox-badge')).toHaveText('1')
  await say(page, 'The visit report and photos are saved on the phone', 1400)
  await context.setOffline(false)
  await page.getByTestId('tab-outbox').click()
  await expect(page.getByTestId('outbox-waiting')).toContainText('0', { timeout: 60_000 })
  await say(page, 'Back online: the visit syncs, with the distance, photo and duplicate checks run on the server', 1800)
  await logout(page)

  // ---- credit officer ----
  await login(page, 'co1', 'Credit officer')
  await openApp(page, name)
  const run = page.getByTestId('act-runDecision')
  if (await run.isVisible()) { await run.click(); await expect(page.getByTestId('ok')).toBeVisible({ timeout: 60_000 }) }
  await page.getByTestId('go-decision').click()
  await expect(page.getByTestId('decision-outcome')).toBeVisible({ timeout: 30_000 })
  await say(page, 'Decision on the phone: score, recommended amount, FOIR and reasons', 2200)
  const sign = page.getByTestId('sign-memo')
  if (await sign.isVisible()) {
    await sign.click()
    await expect(page.getByTestId('ok').or(page.getByTestId('error'))).toBeVisible()
    await say(page, 'Credit memo signed on the phone', 900)
  }
  await page.getByTestId('back').click()
  await logout(page)

  // ---- credit manager ----
  await login(page, 'cm1', 'Credit manager')
  await openApp(page, name)
  const sanction = page.getByTestId('act-sanction')
  if (await sanction.isVisible()) {
    await say(page, 'The credit manager sanctions from the phone', 1200)
    await sanction.click()
    await page.getByTestId('f-note').fill('Cash flows, bureau and the field visit support the recommended amount; sanction as recommended')
    await page.getByTestId('confirm').click()
    await expect(page.getByTestId('ok')).toContainText('Sanction')
  }
  await page.getByTestId('go-kfs').click()
  await expect(page.getByTestId('kfs-emi')).toBeVisible()
  await say(page, 'Key Fact Statement: EMI, APR and charges', 1800)
  await page.getByTestId('back').click()
  await logout(page)

  // ---- borrower accepts the KFS on the sales officer's phone ----
  await login(page, 'sales1', 'Sales officer')
  await openApp(page, name)
  await page.getByTestId('act-acceptKfs').click()
  await page.getByTestId('f-otp').fill('123456')
  await say(page, 'The borrower reads the KFS and accepts it with the OTP', 1200)
  await page.getByTestId('confirm').click()
  await expect(page.getByTestId('ok')).toBeVisible()
  await logout(page)

  // ---- operations disburses ----
  await login(page, 'ops1', 'Operations')
  await openApp(page, name)
  await page.getByTestId('act-disburse').click()
  await expect(page.getByTestId('ok')).toContainText('UTR', { timeout: 30_000 })
  await expect(page.getByTestId('app-state')).toContainText('Disbursed')
  await say(page, 'Disbursed. End of the mobile walkthrough: the same case, every role, on a phone', 2800)
})
