import path from 'path'
import { expect, test, type Page } from '@playwright/test'

const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
}

async function openApp(page: Page, name: string) {
  await page.goto('/applications')
  await page.getByPlaceholder('Search name, PAN or application no.').fill(name)
  await page.getByRole('cell', { name, exact: true }).first().click()
  await expect(page.getByRole('heading', { name: new RegExp(name) })).toBeVisible()
}

const state = (page: Page, domain: string) => page.locator(`[data-domain="${domain}"] .pill`)

test('demo data is visible in the pipeline', async ({ page }) => {
  await login(page, 'ops1')
  await page.goto('/applications')
  await expect(page.getByRole('cell', { name: 'Priya Nair' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'Ramesh Patil' })).toBeVisible()
})

test('new application: capture, documents, automated checks, decision', async ({ page }) => {
  await login(page, 'sales1')
  await page.getByRole('link', { name: 'New application' }).click()
  await page.locator('select[name=productCode]').selectOption('PL')
  await page.locator('input[name=loanAmount]').fill('200000')
  await page.locator('input[name=tenureMonths]').fill('24')
  await page.locator('input[name=applicantName]').fill('Deepa Menon')
  await page.locator('input[name=pan]').fill('DEEPM4321D')
  await page.locator('input[name=mobile]').fill('9876500011')
  await page.locator('input[name=dob]').fill('1990-04-15')
  await page.locator('input[name=city]').fill('Pune')
  await page.locator('input[name=businessVintageYears]').fill('5')
  await page.locator('input[name=declaredMonthlyIncome]').fill('80000')
  await page.locator('input[name=essentialExpenses]').fill('25000')
  await page.locator('input[name=bankAccountNo]').fill('123456789012')
  await page.locator('input[name=bankIfsc]').fill('ICIC0000123')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await page.getByRole('button', { name: 'Save and submit' }).click()
  await expect(page.getByRole('heading', { name: /Deepa Menon/ })).toBeVisible()
  await expect(state(page, 'APP')).toHaveText('Submitted')

  await page.getByRole('tab', { name: 'Documents' }).click()
  for (const [t, f] of [['PAN', 'deepa-pan.png'], ['AADHAAR', 'deepa-aadhaar.png'], ['SALARY_SLIP', 'deepa-salary.png']]) {
    await page.getByLabel('Document type').selectOption(t)
    await page.getByLabel('File').setInputFiles(fixture(f))
    await page.getByRole('button', { name: 'Upload', exact: true }).click()
    await expect(page.locator(`[data-doc="${t}"]`)).toBeVisible()
  }
  await logout(page)

  await login(page, 'ops1')
  await openApp(page, 'Deepa Menon')
  await page.locator('[data-action=process]').click()
  await expect(page.getByRole('status')).toContainText('Decision engine', { timeout: 30_000 })
  await expect(state(page, 'DOCS')).toHaveText('Complete')
  await expect(state(page, 'KYC')).toHaveText('Verified')
  await expect(state(page, 'DECISION')).not.toHaveText('Pending')

  // the AI read the documents; Aadhaar is masked
  await page.getByRole('tab', { name: 'Documents' }).click()
  await expect(page.locator('[data-doc=PAN] [data-field=pan]')).toHaveText('DEEPM4321D')
  await expect(page.locator('[data-doc=AADHAAR] [data-field=aadhaarMasked]')).toContainText('XXXX XXXX')
  await expect(page.locator('[data-doc=AADHAAR]')).toContainText('Aadhaar masked')
  await page.getByRole('tab', { name: 'Data' }).click()
  await expect(page.getByText('KYC checks from documents')).toBeVisible()
  await expect(page.getByRole('cell', { name: 'PAN on card matches application' })).toBeVisible()
  await page.getByRole('tab', { name: 'Decision' }).click()
  await expect(page.getByText('What drove the risk')).toBeVisible()
  await page.getByRole('tab', { name: 'Credit memo' }).click()
  await expect(page.locator('pre.memo')).toContainText('CREDIT MEMO')
})

test('L2 sanction, KFS acceptance and disbursement', async ({ page }) => {
  await login(page, 'co1')
  await openApp(page, 'Ramesh Patil')
  await expect(state(page, 'SANCTION')).toHaveText('Pending l2')
  await expect(page.locator('[data-action=sanction]')).toHaveCount(0)
  await logout(page)

  await login(page, 'cm1')
  await openApp(page, 'Ramesh Patil')
  await page.locator('[data-action=sanction]').click()
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Sanctioned')
  await page.getByRole('tab', { name: 'Sanction & KFS' }).click()
  await expect(page.getByText('APR (all-in)')).toBeVisible()
  await logout(page)

  await login(page, 'sales1')
  await openApp(page, 'Ramesh Patil')
  await page.locator('[data-action=acceptKfs]').click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(state(page, 'SANCTION')).toHaveText('Kfs accepted')
  await logout(page)

  await login(page, 'ops1')
  await openApp(page, 'Ramesh Patil')
  await page.locator('[data-action=disburse]').click()
  await expect(page.getByRole('status')).toContainText('UTR')
  await expect(state(page, 'APP')).toHaveText('Disbursed')
})

test('dead-letter queue: failed AA call is retried from the integration log', async ({ page }) => {
  await login(page, 'ops1')
  await page.goto('/integrations')
  await page.getByLabel('Status').selectOption('DLQ')
  const retry = page.locator('[data-retry]').first()
  await expect(retry).toBeVisible()
  await retry.click()
  await expect(page.getByText(/retried/)).toBeVisible()
  await openApp(page, 'Meera Shah')
  await expect(state(page, 'DATA')).toHaveText('Fetched')
})

test('roles: sales cannot open user admin', async ({ page }) => {
  await login(page, 'sales1')
  await expect(page.getByRole('link', { name: 'Users' })).toHaveCount(0)
  await page.goto('/admin/users')
  await expect(page.getByRole('alert')).toContainText('not allowed')
})

test('document review: an edited salary slip waits for a person, who rejects it', async ({ page }) => {
  await login(page, 'ops1')
  await openApp(page, 'Anil Kumar')
  await expect(state(page, 'DOCS')).toHaveText('In review')
  await page.getByRole('tab', { name: 'Documents' }).click()
  await expect(page.locator('[data-doc=SALARY_SLIP]')).toContainText('editing software')
  await page.locator('[data-review=SALARY_SLIP]').click()
  await page.locator('textarea[name=note]').fill('Slip was edited in Photoshop; ask for the original from employer')
  await page.getByRole('button', { name: 'Reject' }).click()
  await expect(page.locator('[data-doc=SALARY_SLIP]')).toContainText('Rejected')
  await expect(state(page, 'DOCS')).toHaveText('Deficient')
})
