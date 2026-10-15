import { expect, test, type Page } from '@playwright/test'

// Loan document templates: library, editing with fields and clauses, approval, product documents.

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

test('legal drafts a new welcome letter version, compliance approves it, products list their documents', async ({ page }) => {
  test.setTimeout(90_000)
  await login(page, 'legal1')
  await page.goto('/admin/doc-templates')
  await expect(page.getByRole('heading', { name: 'Document templates' })).toBeVisible()
  await expect(page.getByTestId('doc-template-list')).toContainText('Loan agreement')
  await page.locator('[data-doctpl-version="WELCOME_LETTER-en-1"]').click()
  await page.locator('[data-doctpl=new-version]').click()
  await expect(page.getByTestId('doctpl-toolbar')).toBeVisible()
  // switch to HTML and add a line with a field and a condition
  await page.getByRole('button', { name: 'Edit HTML' }).click()
  const src = page.locator('textarea[name=tplSource]')
  await src.fill((await src.inputValue()) + '<p>Your branch manager at {{branch.nam}} will call you.</p>')
  await page.locator('textarea[name=tplNotes]').fill('Add a line about the branch manager call')
  await page.locator('[data-doctpl=save]').click()
  await expect(page.getByTestId('doctpl-problems')).toContainText('Unknown field {{branch.nam}}')
  await src.fill((await src.inputValue()).replace('{{branch.nam}}', '{{branch.name}}'))
  await page.locator('[data-doctpl=preview]').click()
  await expect(page.getByTestId('doctpl-preview')).toContainText('sample values')
  await expect(page.frameLocator('[data-testid=doctpl-preview] iframe').locator('body')).toContainText('Your branch manager at Pune Kothrud will call you')
  await page.locator('[data-doctpl=submit]').click()
  await expect(page.getByRole('status')).toContainText('Sent for approval')
  await logout(page)

  await login(page, 'comp1')
  await page.goto('/admin/doc-templates')
  await page.locator('[data-doctpl-version="WELCOME_LETTER-en-2"]').click()
  await page.locator('input[name=tplDecision]').fill('Wording checked by compliance')
  await page.locator('[data-doctpl=approve]').click()
  await expect(page.locator('[data-doctpl-version="WELCOME_LETTER-en-2"]')).toContainText('approved')
  await expect(page.locator('[data-doctpl-version="WELCOME_LETTER-en-1"]')).toContainText('retired')
  // clause library
  await page.getByRole('tab', { name: 'Clause library' }).click()
  await expect(page.getByTestId('doc-template-list')).toContainText('Clause: interest')

  // products: LAP uses the mortgage memorandum
  await logout(page)
  await login(page, 'admin')
  await page.goto('/admin/products')
  await page.getByRole('cell', { name: /Loan against property/ }).first().click()
  await page.getByRole('tab', { name: 'Loan documents' }).click()
  await expect(page.getByTestId('product-loan-docs')).toContainText('Memorandum of deposit of title deeds')
  await expect(page.getByTestId('product-loan-docs')).toContainText('Loan agreement')
})
