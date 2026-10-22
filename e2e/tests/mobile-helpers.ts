import { expect, test, type Page } from '@playwright/test'
import { fixture, say } from './asset-journey'

// Shared steps for the recorded mobile-app journeys (the React Native screens in a phone-sized browser on port 3002).
export const MOBILE = process.env.MOBILE_URL ?? 'http://localhost:3002'

export function phone(geo: { latitude: number; longitude: number }) {
  test.use({
    baseURL: MOBILE,
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2,
    video: { mode: 'on', size: { width: 390, height: 844 } }, launchOptions: { slowMo: 120, args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--disable-features=WebRtcHideLocalIpsWithMdns'] }, actionTimeout: 30_000,
    permissions: ['geolocation', 'camera', 'microphone'], geolocation: { ...geo, accuracy: 12 },
  })
}

export async function captions(page: Page) {
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

export async function login(page: Page, user: string, who: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await say(page, `On the phone: ${who} (${user}) signs in`, 900)
  await page.getByTestId('sign-in').click()
  await expect(page.getByRole('tablist')).toBeVisible()
}
export async function logout(page: Page) {
  await page.getByTestId('tab-me').click()
  await page.getByTestId('sign-out').click()
  await expect(page.getByTestId('sign-in')).toBeVisible()
}
export async function openApp(page: Page, name: string) {
  await page.getByTestId('tab-apps').click()
  await page.getByTestId('search').fill(name)
  await page.getByTestId('search').press('Enter')
  await page.getByRole('button', { name: new RegExp('^' + name) }).first().click()
  await expect(page.getByTestId('app-detail')).toBeVisible()
}
export async function pick(page: Page, testId: string, file: string) {
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId(testId).click()
  await (await chooser).setFiles(fixture(file))
}
export const radio = (page: Page, name: string, scope?: string) =>
  (scope ? page.getByTestId(scope) : page).getByRole('radio', { name, exact: true }).first().click()
export async function back(page: Page) { await page.getByTestId('back').click() }

export interface NewApp {
  product: string; amount: string; tenure: string; purpose: string; name: string; pan: string; mobile: string; dob: string; gender: 'Male' | 'Female'
  address: string; city: string; pincode: string; business: string; industry: string; vintage: string; income: string; expenses: string
  account: string; ifsc: string; docs: [string, string][]; submit?: boolean
  people?: (page: Page) => Promise<void>
  captions?: { loan?: string; applicant?: string; income?: string; docs?: string }
}

/** Sales officer: the new-application wizard on the phone, online. Ends on the application. */
export async function newApplication(page: Page, c: NewApp) {
  await page.getByTestId('tab-new').click()
  await page.getByTestId('f-product').click()
  await page.getByTestId('f-product-search').fill(c.product)
  await page.getByRole('button', { name: c.product, exact: true }).click()
  await page.getByTestId('f-loanAmount').fill(c.amount)
  await page.getByTestId('f-tenureMonths').fill(c.tenure)
  await page.getByTestId('f-purpose').fill(c.purpose)
  if (c.captions?.loan) await say(page, c.captions.loan, 1600)
  await page.getByTestId('next').click()
  await page.getByTestId('f-applicantName').fill(c.name)
  await page.getByTestId('f-pan').fill(c.pan)
  await page.getByTestId('f-mobile').fill(c.mobile)
  await page.getByTestId('f-dob').fill(c.dob)
  await radio(page, c.gender)
  await page.getByTestId('f-address').fill(c.address)
  await page.getByTestId('f-city').fill(c.city)
  await page.getByTestId('f-pincode').fill(c.pincode)
  if (c.captions?.applicant) await say(page, c.captions.applicant, 1200)
  await page.getByTestId('next').click()
  await page.getByTestId('f-businessName').fill(c.business)
  await radio(page, c.industry)
  await page.getByTestId('f-businessVintageYears').fill(c.vintage)
  await page.getByTestId('f-declaredMonthlyIncome').fill(c.income)
  await page.getByTestId('f-essentialExpenses').fill(c.expenses)
  if (c.captions?.income) await say(page, c.captions.income, 1400)
  await page.getByTestId('next').click()
  if (c.people) await c.people(page)
  await page.getByTestId('next').click()
  await page.getByTestId('f-bankAccountNo').fill(c.account)
  await page.getByTestId('f-bankIfsc').fill(c.ifsc)
  for (const x of ['KYC', 'Credit bureau', 'Account Aggregator']) await page.getByRole('checkbox', { name: new RegExp('^' + x) }).click()
  await page.getByTestId('next').click()
  for (const [t, f] of c.docs) {
    await radio(page, t, 'new-doc-type')
    await pick(page, 'new-doc-file', f)
    await expect(page.getByText(f)).toBeVisible()
  }
  if (c.captions?.docs) await say(page, c.captions.docs, 1400)
  await page.getByTestId('next').click()
  await page.getByTestId(c.submit === false ? 'save-draft' : 'save-submit').click()
  await expect(page.getByTestId('app-detail')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByTestId('outbox-badge')).toHaveCount(0, { timeout: 90_000 })
}

/** Approve, sanction (if it waits for this officer), KFS by OTP and disbursement, each on the phone. */
export async function sanctionToDisbursement(page: Page, name: string, o: { sanctioner?: string; note: string; kfs?: string; end: string }) {
  const who = o.sanctioner ?? 'cm1'
  await login(page, who, who === 'cro1' ? 'CRO' : 'Credit manager')
  await openApp(page, name)
  const s = page.getByTestId('act-sanction')
  if (await s.isVisible()) {
    await s.click()
    await page.getByTestId('f-note').fill(o.note)
    await page.getByTestId('confirm').click()
    await expect(page.getByTestId('ok')).toContainText('Sanction')
  }
  await page.getByTestId('go-kfs').click()
  await expect(page.getByTestId('kfs-emi')).toBeVisible()
  if (o.kfs) await say(page, o.kfs, 2000)
  await back(page)
  await logout(page)

  await login(page, 'sales1', 'Sales officer')
  await openApp(page, name)
  await page.getByTestId('act-acceptKfs').click()
  await page.getByTestId('f-otp').fill('123456')
  await say(page, 'The borrower reads the KFS and accepts it with the OTP', 1200)
  await page.getByTestId('confirm').click()
  await expect(page.getByTestId('ok')).toBeVisible()
  await logout(page)

  await login(page, 'ops1', 'Operations')
  await openApp(page, name)
  await page.getByTestId('act-disburse').click()
  await expect(page.getByTestId('ok')).toContainText('UTR', { timeout: 30_000 })
  await expect(page.getByTestId('app-state')).toContainText('Disbursed')
  await say(page, o.end, 2800)
}

/** Credit officer: decision (run it if it waits) and the memo signed. */
export async function decide(page: Page, name: string, caption: string) {
  await login(page, 'co1', 'Credit officer')
  await openApp(page, name)
  const run = page.getByTestId('act-runDecision')
  if (await run.isVisible()) { await run.click(); await expect(page.getByTestId('ok').or(page.getByTestId('error'))).toBeVisible({ timeout: 60_000 }) }
  await page.getByTestId('go-decision').click()
  await expect(page.getByTestId('decision-outcome')).toBeVisible({ timeout: 30_000 })
  await say(page, caption, 2200)
  const sign = page.getByTestId('sign-memo')
  if (await sign.isVisible()) {
    await sign.click()
    await expect(page.getByTestId('ok').or(page.getByTestId('error'))).toBeVisible()
    await say(page, 'Credit memo signed on the phone', 900)
  }
  await back(page)
  await logout(page)
}

/** Operations: the automated checks. */
export async function runChecks(page: Page, name: string, caption: string) {
  await login(page, 'ops1', 'Operations')
  await openApp(page, name)
  await say(page, caption, 1400)
  await page.getByTestId('act-process').click()
  await expect(page.getByTestId('ok')).toContainText('Decision engine', { timeout: 90_000 })
  await logout(page)
}
