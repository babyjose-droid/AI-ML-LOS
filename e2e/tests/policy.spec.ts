import { expect, test, type Page } from '@playwright/test'

// Phase 5: credit policy studio. A credit manager drafts and simulates, the CRO approves, the change goes live.

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

test('policy studio: draft, simulate, maker-checker approval, live', async ({ page }) => {
  test.setTimeout(120_000)
  await login(page, 'cm1')
  await page.getByRole('link', { name: 'Credit policy studio' }).click()
  await page.locator('[data-policy=new]').click()
  await expect(page.locator('[data-policy-detail=DRAFT]')).toBeVisible()

  // a lenient, low-impact change so later tests are unaffected: allow one more bureau enquiry
  await page.locator('input[name=maxEnq6]').fill('7')
  await page.locator('textarea[name=policyNotes]').fill('Allow 7 enquiries: festive-season shopping around')
  await page.locator('[data-policy=save]').click()
  await expect(page.getByText('Saved. Run the simulation again')).toBeVisible()
  await expect(page.locator('[data-policy=submit]')).toBeDisabled()
  await page.locator('[data-policy=simulate]').click()
  await expect(page.locator('[data-policy-simulation]')).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('[data-policy-simulation]')).toContainText('2000 past loans')
  await expect(page.locator('[data-policy-simulation]')).toContainText('Actual bad rate of approved')
  await page.locator('[data-policy=submit]').click()
  await expect(page.locator('[data-policy-detail=PENDING_APPROVAL]')).toBeVisible()
  await expect(page.locator('[data-policy=approve]')).toHaveCount(0) // the drafter cannot approve
  await logout(page)

  await login(page, 'cro1')
  await expect(page.getByText(/waiting for your approval/)).toBeVisible()
  await page.getByText(/waiting for your approval/).click()
  await expect(page.locator('[data-policy-changes]')).toContainText('Maximum bureau enquiries in 6 months')
  await page.locator('textarea[name=decisionNote]').fill('Reviewed simulation: negligible change in bad rate')
  await page.locator('[data-policy=approve]').click()
  await expect(page.locator('[data-policy-detail=LIVE]')).toBeVisible()
  await expect(page.getByText('Retired', { exact: true }).first()).toBeVisible()
})
