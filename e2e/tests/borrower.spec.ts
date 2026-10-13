import path from 'path'
import { expect, test } from '@playwright/test'

// Phase 5: borrower self-service portal on a phone-sized screen.
const fixture = (f: string) => path.join(__dirname, '..', 'fixtures', f)

test('borrower portal: OTP sign-in, apply, upload documents, follow progress', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const page = await ctx.newPage()
  await page.goto('/apply')
  await page.locator('input[name=mobile]').fill('9847011111')
  await page.getByRole('button', { name: 'Send OTP' }).click()
  await page.locator('input[name=otp]').fill('123456')
  await page.getByRole('button', { name: 'Verify and continue' }).click()
  await expect(page.getByRole('heading', { name: 'Your loans' })).toBeVisible()

  await page.locator('[data-borrower=apply]').click()
  await page.locator('select[name=productCode]').selectOption('PL')
  const fill = async (n: string, v: string) => page.locator(`input[name=${n}]`).fill(v)
  await fill('loanAmount', '150000')
  await fill('tenureMonths', '18')
  await fill('purpose', 'Medical expenses')
  await fill('applicantName', 'Kavya Rao')
  await fill('pan', 'BORPK5566K')
  await fill('dob', '1994-07-21')
  await fill('city', 'Bengaluru')
  await fill('pincode', '560034')
  await fill('address', '12 Koramangala')
  await fill('businessName', 'Bright Labs')
  await fill('businessVintageYears', '3')
  await fill('declaredMonthlyIncome', '65000')
  await fill('essentialExpenses', '22000')
  await fill('bankAccountNo', '50100123456789')
  await fill('bankIfsc', 'HDFC0001234')
  for (const c of ['consentKyc', 'consentAa', 'consentBureau']) await page.locator(`input[name=${c}]`).check()
  await page.locator('[data-borrower=submit]').click()
  await expect(page.locator('[data-borrower-stage]')).toBeVisible()
  await expect(page.getByText('Application received.')).toBeVisible()
  await expect(page.getByText(/Still needed/)).toBeVisible()

  await page.locator('select[name=docType]').selectOption('PAN')
  await page.getByLabel('File').setInputFiles(fixture('kavya-pan.png'))
  await page.locator('[data-borrower=upload]').click()
  await expect(page.locator('.borrower-doc')).toContainText('PAN card')

  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(over).toBeLessThanOrEqual(1)

  // a staff page is not reachable with the borrower's sign-in
  const r = await page.request.get('/api/applications', { headers: { Authorization: 'Bearer ' + await page.evaluate(() => localStorage.getItem('rhythm_borrower_token')) } })
  expect(r.status()).toBe(403)
  await ctx.close()
})
