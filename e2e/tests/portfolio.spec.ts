import { expect, test, type Page } from '@playwright/test'

// Phase 3: the loan book from a (demo) LMS feed, collections, the compliance pack and the policy assistant.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

test('portfolio: demo LMS feed, loan book, collections queue', async ({ page }) => {
  test.setTimeout(120_000)
  await login(page, 'ops1')
  await page.goto('/portfolio')
  await expect(page.getByRole('heading', { name: 'Loan book' })).toBeVisible()
  await Promise.all([
    page.waitForResponse(r => r.url().includes('/api/book/feed/demo') && r.status() === 200, { timeout: 90_000 }),
    page.locator('[data-action=demoFeed]').click(),
  ])
  await expect(page.getByText(/Demo LMS feed for/)).toBeVisible()
  await expect(page.getByTestId('book-summary')).toContainText('Gross NPA')
  await page.getByRole('tab', { name: 'Loans' }).click()
  await expect(page.locator('table tbody tr').first()).toBeVisible()
  await page.getByRole('tab', { name: 'Collections' }).click()
  await expect(page.getByText('Calls and visits only between 8 am and 7 pm.')).toBeVisible()
})

test('compliance pack and policy assistant', async ({ page }) => {
  await login(page, 'comp1')
  await page.goto('/compliance')
  await expect(page.getByTestId('pack-summary')).toContainText('Passed')
  await expect(page.getByText('Audit trail cannot be changed or deleted')).toBeVisible()
  await page.goto('/assistant')
  await page.getByLabel('Question').fill('What does RC-023 mean?')
  await page.getByRole('button', { name: 'Ask' }).click()
  await expect(page.locator('[data-answer]').first()).toContainText('Money rotation')
})
