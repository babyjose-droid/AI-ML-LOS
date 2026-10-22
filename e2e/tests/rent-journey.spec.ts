import { expect, type Page } from '@playwright/test'
import { fixture, openApp, say, state } from './asset-journey'
import { planJourney } from './plan-journey'
import { openSection } from './sections'

// Rental income-backed loan (lease rental discounting): a shop unit leased to a bank; the rent repays the loan through an escrow.
const NAME = 'Rajendra Bhalerao'
async function openCollateral(page: Page) {
  await page.goto('/collateral?tab=cases')
  await page.locator('table tbody tr', { hasText: NAME }).first().click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
}

planJourney({
  title: 'Rental income-backed loan journey (recorded): lease deed AI, rent covers the EMI, escrow, commercial property via Collateral AI, sanction, disbursement',
  intro: 'Step 1 · Sales captures a lease rental discounting loan: the borrower’s shop unit is leased to a bank',
  product: 'RENT', name: NAME, pan: 'RAJPB4419B', mobile: '9876500966', dob: '1966-10-14', gender: 'MALE', city: 'Nashik', pincode: '422101',
  address: 'Bungalow 8, Model Colony', business: 'Bhalerao Properties', industry: 'SERVICES', vintage: '14', income: '90000', expenses: '15000',
  account: '50200966778899', ifsc: 'HDFC0001234', amount: '2000000', tenure: '120', purpose: 'Business expansion against the rent of Unit G-2',
  amountCaption: '₹20 lakh for 10 years, repaid from the rent the bank pays every month',
  kyc: [['PAN', 'rent-rajendra-pan.png'], ['AADHAAR', 'rent-rajendra-aadhaar.png']],
  planTitle: 'Lease & rent', disbTitle: 'Disbursement',
  docs: [{ type: 'LEASE_DEED', file: 'lease-deed.pdf', title: 'LEASE DEED (REGISTERED)', issuer: 'Sub-Registrar Nashik 1 (fictional specimen)',
    lines: ['Lessor: Rajendra Bhalerao', 'Lessee: Fictional Bank Ltd (Nashik Road branch)', 'Premises: Unit G-2, Sai Plaza, Nashik Road', 'Monthly rent: 60,000',
      'Start date: 01-04-2024', 'End date: 31-03-2038', 'Lock-in period: 10 years', 'Escalation: 5% every 3 years'] }],
  fields: [['tenantType', 'BANK'], ['escrowAccount', 'ESC-50200099112233 (HDFC, Nashik Road)'], ['propertyAddress', 'Unit G-2, Sai Plaza, Nashik Road']],
  planCaption: 'The registered lease deed read by AI: landlord, tenant, ₹60,000 rent, 2024 to 2038, 10-year lock-in, escalation',
  checksCaption: 'Lease checks: borrower is the landlord, EMI within 80% of the rent after TDS, loan ends before the lease, lock-in, escrow, a bank as tenant',
  afterPlan: async page => {
    await openSection(page, 'Collateral')
    await page.locator('[data-action=declareCollateral]').click()
    const form = page.locator('.panel', { has: page.getByRole('heading', { name: 'Declare collateral' }) })
    await form.getByLabel('Type').selectOption('SHOP')
    await form.getByLabel('Description').fill('Unit G-2, Sai Plaza (leased to a bank)')
    await form.getByLabel('Owner as declared').fill(NAME)
    await form.getByLabel('Address').fill('Unit G-2, Sai Plaza, Nashik Road, Nashik')
    await form.getByLabel('PIN code').fill('422101')
    await form.getByLabel('Latitude').fill('19.9470')
    await form.getByLabel('Longitude').fill('73.8410')
    await form.getByLabel('Survey / CTS no.').fill('CTS 5510/2')
    await form.getByLabel('Municipal ID').fill('NMC-C-40281')
    await form.getByLabel('Area (sq ft)').fill('1450')
    await page.getByRole('button', { name: 'Declare and open the case' }).click()
    await expect(page.getByTestId('collateral-360')).toBeVisible()
    await page.getByRole('tab', { name: 'Documents' }).click()
    const ecTo = new Date(Date.now() - 10 * 86_400_000).toISOString().slice(0, 10)
    const docs: [string, string, Record<string, unknown>, unknown[]?][] = [
      ['SALE_DEED', 'rent-sale-deed.png', { owner: NAME, seller: 'Kanchan Developers', surveyNo: 'CTS5510/2', areaSqft: 1450, registrationNo: '7712/2012', registrationDate: '2012-07-18' }],
      ['EC', 'rent-ec.png', { periodFrom: '2010-01-01', periodTo: ecTo, surveyNo: 'CTS5510/2' }, [{ date: '2012-07-18', type: 'SALE', docNo: '7712/2012', from: 'Kanchan Developers', to: NAME, linkedRef: null }]],
      ['KHATA', 'rent-khata.png', { owner: NAME, municipalId: 'NMC-C-40281' }],
      ['PROPERTY_TAX', 'rent-tax.png', { owner: NAME, municipalId: 'NMC-C-40281', taxPaidUpTo: '2026-27' }],
      ['VALUATION_REPORT', 'rent-valuation.png', { marketValue: 4500000, valuer: 'Er. Prakash Deshmukh' }],
    ]
    const rows = page.locator('.panel.flush table tbody tr')
    for (const [i, [type, file, fields, tx]] of docs.entries()) {
      const up = page.locator('.panel', { has: page.getByRole('heading', { name: 'Upload a document' }) })
      await up.locator('select').first().selectOption(type)
      await up.locator('input[type=file]').setInputFiles(fixture(file))
      await up.getByLabel(/Fields you have checked/).fill(JSON.stringify(fields))
      if (tx) await up.getByLabel(/EC entries/).fill(JSON.stringify(tx))
      await up.getByRole('button', { name: 'Upload and read' }).click()
      await expect(rows).toHaveCount(i + 1, { timeout: 30_000 })
    }
    await say(page, 'The leased unit is the security: sale deed, EC with no charge, khata, tax, valuation', 2000)
  },
  field: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Property vision' }).click()
    const photos: [string, string][] = [['EXTERIOR_FRONT', 'front'], ['SIGNAGE', 'signage'], ['INTERIOR_HALL', 'hall'], ['STREET_VIEW', 'street'], ['NEIGHBOURHOOD', 'neighbourhood']]
    for (const [i, [view, f]] of photos.entries()) {
      const add = page.locator('.panel', { has: page.getByRole('heading', { name: 'Add a photo' }) })
      await add.locator('select').first().selectOption(view)
      await add.locator('input[type=file]').setInputFiles(fixture(`rent-photo-${f}.jpg`))
      await add.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator('.panel.flush table tbody tr')).toHaveCount(i + 1, { timeout: 30_000 })
    }
    await say(page, 'Step 3 · The field officer photographs the unit and confirms the bank branch is operating there', 1800)
    await openApp(page, NAME)
    await page.locator('[data-action=fieldVisit]').click()
    await page.locator('textarea[name=note]').fill('Unit G-2 visited: bank branch operating, signage and ATM in place; branch manager confirmed the lease and rent.')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'FIELD')).toHaveText('Done')
  },
  valuer: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
    await page.getByLabel('Valuer').fill('Er. Prakash Deshmukh (empanelled)')
    await page.getByLabel('Market value (₹)').fill('4500000')
    await page.getByLabel('Realisable value (₹)').fill('4050000')
    await page.getByLabel('Distress value (₹)').fill('3375000')
    await page.locator('[data-action=saveValuation]').click()
    await expect(page.getByText('VAL-', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
    await say(page, 'Step 4 · The valuer values the leased unit at ₹45 lakh', 1600)
  },
  credit: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Risk & decision' }).click()
    await page.getByRole('button', { name: 'Run Collateral AI again' }).click()
    await page.waitForTimeout(1500)
    await page.getByRole('tab', { name: '360 summary' }).click()
    await expect(page.getByTestId('collateral-360')).toContainText(/pass/i, { timeout: 20_000 })
    await say(page, 'Collateral AI: PASS within the shop LTV', 1600)
  },
  sanctioner: ['cro1', 'CRO'],
  kfsCaption: 'KFS with the EMI; conditions: rent into the escrow, assignment of the lease rentals and mortgage',
  disbCaption: 'Disbursed; from now the bank pays the rent into the escrow and the EMI is swept first',
  geo: { latitude: 19.94705, longitude: 73.84105 },
  end: 'End of the rental income-backed loan walkthrough.',
})
