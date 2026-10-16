import { expect, test, type Page } from '@playwright/test'
import { openSection } from './sections'

// Phase 4: video KYC between two browsers (official and customer) with Chromium's fake camera.
// The fake camera shows a test pattern, not a face, so the AI correctly reports no face and the official
// must give a reasoned override to approve.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

test('video KYC: customer joins by link, the official runs the call, AI checks, the official decides', async ({ browser }) => {
  test.setTimeout(150_000)
  const officerCtx = await browser.newContext({ permissions: ['camera', 'microphone'] })
  const officer = await officerCtx.newPage()
  await login(officer, 'ops1')
  await officer.goto('/applications')
  await officer.getByPlaceholder('Search name, PAN or application no.').fill('Suresh Yadav')
  await officer.getByRole('cell', { name: 'Suresh Yadav', exact: true }).first().click()
  await openSection(officer, 'Video KYC')
  await officer.locator('[data-vkyc=create]').click()
  const link = await officer.locator('[data-vkyc-link]').inputValue()
  expect(link).toMatch(/\/vkyc\/[A-Za-z0-9_-]{20,}$/)

  // customer, on another device, in Kanpur
  const customerCtx = await browser.newContext({ permissions: ['camera', 'microphone', 'geolocation'], geolocation: { latitude: 26.4499, longitude: 80.3319, accuracy: 25 } })
  const customer = await customerCtx.newPage()
  await customer.goto(link)
  await expect(customer.getByRole('heading', { name: /Hello Suresh/ })).toBeVisible()
  const join = customer.getByRole('button', { name: /join/ })
  await expect(join).toBeDisabled()
  await customer.getByLabel('I consent').check()
  await join.click()
  await expect(customer.locator('[data-vkyc-stage=waiting]')).toBeVisible()

  // official starts the call once the customer has joined (the tab refreshes every 3 s)
  await expect(officer.locator('[data-vkyc=start]')).toBeVisible({ timeout: 15_000 })
  await officer.locator('[data-vkyc=start]').click()
  await expect(officer.getByText('Connected. Recording.')).toBeVisible({ timeout: 30_000 })
  await expect(customer.locator('[data-vkyc-stage=call]')).toBeVisible({ timeout: 15_000 })
  const code = (await officer.locator('[data-vkyc-code]').innerText()).trim()
  expect(code).toMatch(/^\d{4}$/)

  for (const step of ['front', 'left', 'right', 'code', 'pan']) {
    await officer.locator(`[data-vkyc-step=${step}]`).click()
    if (step === 'left') await expect(customer.locator('[data-vkyc-instruction]')).toContainText('LEFT')
    if (step === 'code') await expect(customer.locator('[data-vkyc-instruction]')).toContainText(code)
    await expect(officer.locator(`[data-vkyc-step=${step}]`)).toContainText('✓', { timeout: 20_000 })
  }
  await officer.locator('[data-vkyc=end]').click()
  await expect(officer.locator('[data-vkyc-detail=COMPLETED]')).toBeVisible({ timeout: 90_000 })
  await expect(customer.locator('[data-vkyc-stage=ended]')).toBeVisible({ timeout: 15_000 })

  // AI: the test pattern has no face, so it is sent to the official with reasons
  await expect(officer.locator('[data-vkyc-reasons]')).toContainText('No face')
  await expect(officer.locator('.vkyc-frames figure')).toHaveCount(18)
  await expect(officer.getByText(/Play \(\d+ KB\)/)).toBeVisible()

  // the product's questions are shown to the official and each must be confirmed
  await expect(officer.locator('[data-vkyc-question]')).toHaveCount(3)
  await officer.locator('input[name=codeHeard]').check()
  await officer.locator('textarea[name=vkycNote]').fill('Test camera without a face; identity confirmed against PAN by the official on the call')
  await officer.locator('[data-vkyc=approve]').click()
  await expect(officer.getByRole('alert')).toContainText('Ask and confirm every question')
  for (const q of await officer.locator('[data-vkyc-question]').all()) await q.check()

  // approving against the AI needs the code confirmed and a reasoned override
  await officer.locator('textarea[name=vkycNote]').fill('Looks okay to me')
  await officer.locator('[data-vkyc=approve]').click()
  await expect(officer.getByRole('alert')).toContainText('30 characters')
  await officer.locator('textarea[name=vkycNote]').fill('Test camera without a face; identity confirmed against PAN by the official on the call')
  await officer.locator('[data-vkyc=approve]').click()
  await expect(officer.locator('[data-vkyc-detail=APPROVED]')).toBeVisible({ timeout: 15_000 })
  await expect(officer.locator('[data-vkyc-detail=APPROVED]')).toContainText('AI overridden')

  await customerCtx.close()
  await officerCtx.close()
})

test('video KYC settings per product', async ({ page }) => {
  await login(page, 'admin')
  await page.goto('/admin/products')
  await page.getByRole('cell', { name: /Loan against property/ }).first().click()
  await page.getByRole('tab', { name: 'Video KYC' }).click()
  await expect(page.locator('select[name=vk_parties]')).toHaveValue('ALL')
  await expect(page.locator('input[name=vk_face]')).toHaveValue('0.45')
  await expect(page.locator('textarea[name=vk_questions]')).toContainText('mortgaged')
})
