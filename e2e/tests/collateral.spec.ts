import { expect, test, type Page } from '@playwright/test'

// Phase 4: Collateral AI on the demo LAP cases, the legal review queue, co-lending and the capital plan.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

test('collateral AI: overview, case tabs and the legal review queue', async ({ page }) => {
  await login(page, 'cm1')
  await page.goto('/collateral')
  await expect(page.getByTestId('collateral-kpis')).toContainText('Pass / refer / fail')
  await page.locator('table tbody tr', { hasText: 'Farhan Shaikh' }).first().click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
  await page.getByRole('tab', { name: 'EC analysis' }).click()
  await expect(page.getByText('open: no release')).toBeVisible()
  await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
  await expect(page.getByText('LTV CAP').first()).toBeVisible()
  await page.getByRole('tab', { name: 'Risk & decision' }).click()
  await expect(page.getByText('Risk vector')).toBeVisible()
  await page.getByRole('button', { name: 'Sign out' }).click()

  await login(page, 'legal1')
  await page.goto('/collateral')
  await expect(page.getByTestId('collateral-reviews')).toContainText('CR-04')
})

test('co-lending pool and capital plan', async ({ page }) => {
  await login(page, 'cro1')
  await page.goto('/colending')
  await expect(page.locator('[data-partner=PB-RETAIL]')).toContainText('Blended rate')
  await page.goto('/capital')
  await page.locator('[data-action=project]').click()
  await expect(page.getByTestId('capital-summary')).toContainText('Lowest CRAR')
})
