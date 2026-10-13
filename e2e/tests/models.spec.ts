import { expect, test, type Page } from '@playwright/test'

// Phase 5: risk models. Train a PD model on the (synthetic) loan book with the AI service, compare it with the
// model in use, and the CRO decides. The CRO rejects here so later tests keep the built-in model.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

test('risk models: train a candidate, compare, CRO decides', async ({ page }) => {
  test.setTimeout(150_000)
  await login(page, 'cm1')
  await page.goto('/admin/models')
  await expect(page.locator('[data-model=inuse]')).toContainText('built-in')
  await page.locator('[data-model=train]').click()
  await expect(page.locator('[data-model-detail=CANDIDATE]')).toBeVisible({ timeout: 120_000 })
  await expect(page.getByText('On the hold-out loans')).toBeVisible()
  await expect(page.getByText('AUC (ranking power)')).toBeVisible()
  await expect(page.getByText('What the model learned')).toBeVisible()
  await expect(page.locator('[data-model=approve]')).toHaveCount(0)
  await page.getByRole('button', { name: 'Sign out' }).click()

  await login(page, 'cro1')
  await page.goto('/admin/models')
  await page.locator('textarea[name=modelNote]').fill('Synthetic book only: wait for the NBFC history')
  await page.locator('[data-model=reject]').click()
  await expect(page.locator('[data-model-detail=REJECTED]')).toBeVisible()
  await expect(page.locator('[data-model=inuse]')).toContainText('built-in')
})
