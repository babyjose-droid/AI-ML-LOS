import { expect, test } from '@playwright/test'
import { say } from './asset-journey'
import { back, captions, login, logout, newApplication, openApp, phone, pick, radio } from './mobile-helpers'

/**
 * Recorded on the mobile app: a co-applicant added in the new-application wizard, a guarantor added afterwards, the
 * co-applicant's consent by OTP and her PAN photographed, the guarantor's consent through an invite link on his own
 * phone, then video KYC run by operations from the phone with the customer on another device (fake camera: the AI
 * finds no face, so the officer approves with a reasoned override). Fictional people and SPECIMEN documents.
 */
phone({ latitude: 20.0059, longitude: 73.7654 })
const NAME = 'Vikram Shinde'

test('Mobile co-applicants, guarantor and video KYC (recorded): wizard, OTP consent, documents, invite link, live call from the phone', async ({ page, browser }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)

  await login(page, 'sales1', 'Sales officer')
  await say(page, 'A ₹6 lakh business loan with his wife as co-applicant; above ₹5 lakh a guarantor is needed before sanction', 2400)
  await newApplication(page, {
    product: 'Business loan (unsecured)', amount: '600000', tenure: '36', purpose: 'Second outlet on College Road', name: NAME, pan: 'VIKPS7720N', mobile: '9876531803',
    dob: '1983-10-19', gender: 'Male', address: 'Shop 3, College Road', city: 'Nashik', pincode: '422005', business: 'Shinde Mobile Gallery', industry: 'Trading',
    vintage: '8', income: '95000', expenses: '30000', account: '50100318031223', ifsc: 'HDFC0001234',
    docs: [['PAN', 'mob-vikram-pan.png'], ['Aadhaar', 'mob-vikram-aadhaar.png']],
    people: async p => {
      await say(p, 'Co-applicants and guarantors are added right in the wizard, with the product’s rules shown', 1600)
      await p.getByTestId('party-form-add').click()
      await radio(p, 'Spouse', 'party1-relationship')
      await p.getByTestId('party1-name').fill('Pooja Shinde')
      await p.getByTestId('party1-pan').fill('POOPS7721R')
      await p.getByTestId('party1-mobile').fill('9876531804')
      await p.getByTestId('party1-dob').fill('1986-04-25')
      await radio(p, 'Female', 'party1-gender')
      await p.getByTestId('party1-occupation').fill('Boutique owner')
      await p.getByTestId('party1-monthlyIncome').fill('40000')
      await p.getByRole('checkbox', { name: /^Count this income/ }).click()
      await say(p, 'His wife joins as co-applicant; her income counts once her own bank data confirms it', 1800)
    },
  })
  await page.getByTestId('go-parties').click()
  await expect(page.getByTestId('party-P-1')).toContainText('Pooja Shinde')
  await expect(page.getByTestId('party-issues')).toContainText('guarantor')
  await say(page, 'Still needed: a guarantor before sanction, and her consent', 1600)

  // the guarantor
  await page.getByTestId('party-add').click()
  await radio(page, 'Guarantor', 'party1-role')
  await radio(page, 'Parent', 'party1-relationship')
  await page.getByTestId('party1-name').fill('Dilip Shinde')
  await page.getByTestId('party1-pan').fill('DILPS4410D')
  await page.getByTestId('party1-mobile').fill('9876531805')
  await page.getByTestId('party1-dob').fill('1956-08-12')
  await page.getByTestId('party-save').click()
  await expect(page.getByTestId('party-P-2')).toContainText('Dilip Shinde')

  // co-applicant consent by OTP, in person
  await page.getByTestId('consent-btn-P-1').click()
  for (const c of [/^Verify my identity/, /^Check my credit history/, /^Check my income/]) await page.getByRole('checkbox', { name: c }).click()
  await page.getByTestId('party-otp-send').click()
  await page.getByTestId('party-otp').fill('123456')
  await say(page, 'She consents on the spot: the OTP goes to her own phone', 1400)
  await page.getByTestId('party-otp-confirm').click()
  await expect(page.getByTestId('consent-P-1')).toContainText('given')

  // her PAN, photographed
  await page.getByTestId('docs-btn-P-1').click()
  await radio(page, 'PAN', 'party-doc-type')
  await pick(page, 'party-doc-file', 'mob-pooja-pan.png')
  await page.getByTestId('party-doc-upload').click()
  await expect(page.getByTestId('ok')).toContainText('uploaded')
  await say(page, 'Her PAN is read by Document AI and matched with her details', 1400)

  // the guarantor consents on his own phone through a link
  await page.getByTestId('party-P-2').getByRole('button', { name: 'Invite link' }).click()
  await page.getByRole('button', { name: 'Create link' }).click()
  const link = (await page.getByTestId('invite-link').innerText()).trim()
  expect(link).toContain('/party/')
  await say(page, 'The guarantor gets a link for his own phone', 1400)
  const g = await (await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true })).newPage()
  await g.goto(link)
  await expect(g.getByRole('heading', { name: 'Hello Dilip' })).toBeVisible()
  await g.getByRole('button', { name: /Send OTP/ }).click()
  await g.locator('input[name=otp]').fill('123456')
  await g.getByRole('button', { name: 'Verify' }).click()
  await g.getByLabel(/Verify my identity/).check()
  await g.getByLabel(/Check my credit history/).check()
  await g.getByRole('button', { name: 'I agree' }).click()
  await expect(g.getByRole('status')).toContainText('consent is recorded')
  await g.context().close()
  await page.getByTestId('sheet-close').click()
  await back(page)
  await page.getByTestId('go-parties').click()
  await expect(page.getByTestId('consent-P-2')).toContainText('given')
  await say(page, 'Both have consented: KYC and bureau run on each of them', 1600)
  await back(page)
  await logout(page)

  // video KYC from the operations officer's phone
  await login(page, 'ops1', 'Operations')
  await openApp(page, NAME)
  await page.getByTestId('go-vkyc').click()
  const f = page.frameLocator('[data-testid=vkyc-frame]')
  await f.locator('[data-vkyc=create]').click()
  const vlink = await f.locator('[data-vkyc-link]').inputValue()
  expect(vlink).toMatch(/\/vkyc\/[A-Za-z0-9_-]{20,}$/)
  await say(page, 'Video KYC from the phone: a one-time link for the customer', 1600)

  const customerCtx = await browser.newContext({ permissions: ['camera', 'microphone', 'geolocation'], geolocation: { latitude: 20.0059, longitude: 73.7654, accuracy: 20 } })
  const customer = await customerCtx.newPage()
  await customer.goto(vlink)
  await expect(customer.getByRole('heading', { name: /Hello Vikram/ })).toBeVisible()
  await customer.getByLabel('I consent').check()
  await customer.getByRole('button', { name: /join/ }).click()
  await expect(customer.locator('[data-vkyc-stage=waiting]')).toBeVisible()

  await expect(f.locator('[data-vkyc=start]')).toBeVisible({ timeout: 20_000 })
  await f.locator('[data-vkyc=start]').click()
  await expect(f.getByText('Connected. Recording.')).toBeVisible({ timeout: 30_000 })
  await say(page, 'Live call on the phone: the officer asks each step; frames go to the AI', 1600)
  for (const step of ['front', 'left', 'right', 'code', 'pan']) {
    await f.locator(`[data-vkyc-step=${step}]`).click()
    await expect(f.locator(`[data-vkyc-step=${step}]`)).toContainText('✓', { timeout: 20_000 })
  }
  await f.locator('[data-vkyc=end]').click()
  await expect(f.locator('[data-vkyc-detail=COMPLETED]')).toBeVisible({ timeout: 90_000 })
  await expect(f.locator('[data-vkyc-reasons]')).toContainText('No face')
  await say(page, 'The test camera shows no face, so the AI sends it to the officer with its reasons', 2000)
  await f.locator('input[name=codeHeard]').check()
  for (const q of await f.locator('[data-vkyc-question]').all()) await q.check()
  await f.locator('textarea[name=vkycNote]').fill('Test camera without a face; identity confirmed against PAN by the officer on the call')
  await f.locator('[data-vkyc=approve]').click()
  await expect(f.locator('[data-vkyc-detail=APPROVED]')).toBeVisible({ timeout: 15_000 })
  await say(page, 'Approved with a reasoned override, recorded with the call. End of the walkthrough', 2400)
  await customerCtx.close()
})
