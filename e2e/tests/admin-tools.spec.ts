import { expect, test, type Page } from '@playwright/test'

// Templates, cost per file, the video KYC queue and loan history import.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

test('admin edits a customer message and the sanction letter wording', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/admin/templates')
  await expect(page.getByRole('heading', { name: 'Templates' })).toBeVisible()
  await expect(page.getByTestId('letter-templates')).toContainText('intro')
  await page.getByRole('tab', { name: 'Customer messages' }).click()
  await page.locator('[data-template="SMS-VKYC_LINK"]').click()
  await page.locator('textarea[name=templateBody]').fill('Dear {name}, join your video KYC with {lender} here: {link} (valid {hours} hours).')
  await page.getByRole('button', { name: 'Preview' }).click()
  await expect(page.getByTestId('template-preview')).toContainText('Dear Sneha Kulkarni, join your video KYC')
  await page.locator('input[name=templateNote]').fill('Shorter wording')
  await page.locator('[data-action=saveTemplate]').click()
  await expect(page.getByTestId('message-templates')).toContainText('join your video KYC with {lender}')
  // a placeholder the message cannot carry is refused
  await page.locator('[data-template="SMS-OTP_LOGIN"]').click()
  await page.locator('textarea[name=templateBody]').fill('Your code is ready, {name}')
  await page.locator('input[name=templateNote]').fill('Testing a bad edit')
  await page.locator('[data-action=saveTemplate]').click()
  await expect(page.getByTestId('template-editor')).toContainText('must include {otp}')
})

test('benchmarking shows cost per file and the video KYC queue opens', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/benchmarking')
  await expect(page.getByTestId('cost-per-file')).toContainText('Cost of a disbursed file')
  await expect(page.getByTestId('cost-per-file')).toContainText('Where the money goes')
  await page.goto('/vkyc-queue')
  await expect(page.getByRole('heading', { name: 'Video KYC queue' })).toBeVisible()
  await expect(page.getByTestId('vkyc-waiting')).toBeVisible()
})

test('credit manager imports past loans for model training', async ({ page }) => {
  await login(page, 'cm1')
  await page.goto('/admin/models')
  const panel = page.getByTestId('history-import')
  await expect(panel).toContainText('Our own loan history')
  await panel.locator('textarea[name=historyCsv]').fill([
    'ref,segment,amount,tenureMonths,age,declaredMonthlyIncome,monthlyCredits,bureauScore,maxDpd,fraud',
    'E2E-OLD-1,SALARIED,300000,24,33,55000,54000|56000|55500|53000|57000|55000,742,0,N',
    'E2E-OLD-2,MSME,500000,36,41,90000,85000|91000|60000|95000|88000|70000,,120,N',
  ].join('\n'))
  await page.locator('[data-action=importHistory]').click()
  await expect(panel).toContainText('of 2 (1 bad, 0 fraud)')
  await expect(panel).toContainText('import')
})
