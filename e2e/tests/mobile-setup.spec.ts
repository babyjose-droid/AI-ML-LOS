import { expect, test } from '@playwright/test'
import { say } from './asset-journey'
import { captions, login, newApplication, phone, radio } from './mobile-helpers'

/**
 * Recorded on the mobile app: the set-up screens that used to need the web console. A housing loan's construction plan
 * and its stages (released with progress), and an invoice-finance limit with sub-limits on two approved buyers, both
 * entered and checked on the phone. Fictional borrowers.
 */
phone({ latitude: 19.9515, longitude: 73.748 })

test('Mobile set-up screens (recorded): housing plan and construction stages, invoice-finance buyer sub-limits', async ({ page }) => {
  test.setTimeout(10 * 60_000)
  await captions(page)
  await login(page, 'sales1', 'Sales officer')

  await say(page, 'An affordable housing loan to build on his own plot: the plan and stages are set up on the phone', 2000)
  await newApplication(page, {
    product: 'Affordable housing loan', amount: '1500000', tenure: '180', purpose: 'Construction of a house on own plot', name: 'Rohan Kulkarni', pan: 'ROHPK2290K',
    mobile: '9876531806', dob: '1989-09-09', gender: 'Male', address: 'Room 2, Samarth Nagar', city: 'Nashik', pincode: '422010', business: 'Mahindra Components (fictional)',
    industry: 'Salaried', vintage: '6', income: '90000', expenses: '30000', account: '50100318061223', ifsc: 'HDFC0001234', docs: [], submit: false,
  })
  await page.getByTestId('go-plan').click()
  if (!(await page.getByTestId('f-estimate').isVisible())) await page.getByTestId('plan-form-toggle').click()
  await radio(page, 'Construction on own plot', 'f-purpose')
  await page.getByTestId('f-estimate').fill('1800000')
  await page.getByTestId('f-ownContribution').fill('300000')
  await page.getByTestId('f-carpetAreaSqft').fill('860')
  await page.getByTestId('f-builtUpAreaSqft').fill('1050')
  await page.getByTestId('f-planNo').fill('NMC/BP/2026/2210')
  await page.getByTestId('f-propertyAddress').fill('Plot 21, Shivkrupa Nagar, Ambad, Nashik')
  const stages: [string, string, string][] = [['Foundation and plinth', '300000', '0'], ['Roof slab', '450000', '50'], ['Brickwork and plaster', '450000', '80'], ['Finishing', '300000', '100']]
  for (const [i, [l, a, p]] of stages.entries()) {
    if (await page.getByTestId('tr-label-' + (i + 1)).count() === 0) await page.getByTestId('add-tranche').click()
    await page.getByTestId('tr-label-' + (i + 1)).fill(l)
    await page.getByTestId('tr-amount-' + (i + 1)).fill(a)
    await page.getByTestId('tr-pct-' + (i + 1)).fill(p)
  }
  await say(page, '₹18 lakh estimate, ₹3 lakh own contribution; four stages released with construction progress', 2200)
  await page.getByTestId('plan-save').click()
  await expect(page.getByTestId('ok')).toContainText('Plan saved')
  await expect(page.getByTestId('checks')).toBeVisible()
  await say(page, 'Plan checks run at once: size, loan to cost (RBI 90%), stages add up and follow progress', 2600)
  await page.getByTestId('tranche-4').scrollIntoViewIfNeeded()
  await page.waitForTimeout(800)

  await say(page, 'Next: an invoice-finance limit for a packaging maker selling to large buyers', 1800)
  await newApplication(page, {
    product: 'Invoice & receivables finance', amount: '1000000', tenure: '12', purpose: 'Working capital against buyer invoices', name: 'Mehul Shah', pan: 'MEHPS6610M',
    mobile: '9876531807', dob: '1981-01-21', gender: 'Male', address: 'Plot C-7, MIDC Ambad', city: 'Nashik', pincode: '422010', business: 'Shah Packaging (fictional)',
    industry: 'Manufacturing', vintage: '10', income: '350000', expenses: '70000', account: '50200318071223', ifsc: 'HDFC0001234', docs: [], submit: false,
  })
  await page.getByTestId('go-limit').click()
  await expect(page.getByTestId('trade-structure')).toBeVisible()
  await expect(page.getByTestId('f-gstin')).toHaveValue(/^27MEHPS6610M/)
  await page.getByTestId('sub-BYR-WAC').fill('600000')
  await page.getByTestId('sub-BYR-DRM').fill('400000')
  await say(page, 'Sub-limits on two approved buyers: ₹6 lakh and ₹4 lakh', 1800)
  await page.getByTestId('trade-save').click()
  await expect(page.getByTestId('ok')).toContainText('Limit structure saved')
  await expect(page.getByTestId('checks')).toBeVisible()
  await say(page, 'Structure checks: buyers approved, sub-limits within the limit and each buyer’s cap. End of the set-up walkthrough', 2800)
})
