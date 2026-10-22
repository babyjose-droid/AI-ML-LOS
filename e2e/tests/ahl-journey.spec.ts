import { expect, type Page } from '@playwright/test'
import { fixture, openApp, say, state } from './asset-journey'
import { planJourney } from './plan-journey'
import { openSection } from './sections'

// Affordable housing: construction on the borrower's own plot, the property through Collateral AI, stages released with progress.
const NAME = 'Sachin More'
async function openCollateral(page: Page) {
  await page.goto('/collateral?tab=cases')
  await page.locator('table tbody tr', { hasText: NAME }).first().click()
  await expect(page.getByTestId('collateral-360')).toBeVisible()
}

planJourney({
  title: 'Affordable housing loan journey (recorded): construction plan, engineer estimate AI, property via Collateral AI, sanction, first stage, inspection, next stage',
  intro: 'Step 1 · Sales captures an affordable housing loan to build a house on the borrower’s own plot',
  product: 'AHL', name: NAME, pan: 'SACPM5590M', mobile: '9876500811', dob: '1988-02-04', gender: 'MALE', city: 'Nashik', pincode: '422010',
  address: 'Room 6, Ganesh Chawl, Satpur', business: 'Nashik Auto Components Pvt Ltd', industry: 'SALARIED', vintage: '7', income: '95000', expenses: '30000',
  account: '50100811223344', ifsc: 'HDFC0001234', amount: '1500000', tenure: '180', purpose: 'Construction of a house on own plot',
  amountCaption: '₹15 lakh for 15 years against an ₹18 lakh construction estimate',
  kyc: [['PAN', 'ahl-sachin-pan.png'], ['AADHAAR', 'ahl-sachin-aadhaar.png'], ['SALARY_SLIP', 'ahl-sachin-salary.png']],
  planTitle: 'Property & stages', disbTitle: 'First tranche',
  docs: [{ type: 'CONSTRUCTION_ESTIMATE', file: 'construction-estimate.pdf', title: 'CONSTRUCTION ESTIMATE', issuer: 'Er. Sunil Wagh, Licensed Engineer (fictional)',
    lines: ['Engineer: Er. Sunil Wagh', 'Owner: Sachin More', 'Site: Plot 17, Shivkrupa Nagar, Ambad', 'Built-up area: 1,050 sq ft', 'Carpet area: 860 sq ft',
      'Approved plan no: NMC/BP/2026/1187', 'Foundation and plinth: 3,60,000', 'Roof slab: 5,40,000', 'Brickwork and plaster: 5,40,000', 'Finishing: 3,60,000', 'Total estimate: 18,00,000'] }],
  fields: [['purpose', 'CONSTRUCTION'], ['ownContribution', '300000'], ['propertyAddress', 'Plot 17, Shivkrupa Nagar, Ambad, Nashik']],
  tranches: [['Foundation and plinth', '300000', '0'], ['Roof slab', '450000', '50'], ['Brickwork and plaster', '450000', '80'], ['Finishing', '300000', '100']],
  planCaption: 'The engineer’s estimate is read by AI: ₹18 lakh, 860 sq ft carpet area, approved plan number',
  checksCaption: 'Plan checks: affordable size, 83% of cost (RBI limit 90%), own contribution, stages add up and follow progress, estimate matches',
  afterPlan: async page => {
    await openSection(page, 'Collateral')
    await page.locator('[data-action=declareCollateral]').click()
    const form = page.locator('.panel', { has: page.getByRole('heading', { name: 'Declare collateral' }) })
    await form.getByLabel('Type').selectOption('RESIDENTIAL')
    await form.getByLabel('Description').fill('Plot 17 with house under construction')
    await form.getByLabel('Owner as declared').fill(NAME)
    await form.getByLabel('Address').fill('Plot 17, Shivkrupa Nagar, Ambad, Nashik')
    await form.getByLabel('PIN code').fill('422010')
    await form.getByLabel('Latitude').fill('19.9515')
    await form.getByLabel('Longitude').fill('73.7480')
    await form.getByLabel('Survey / CTS no.').fill('S.No. 145/3 Plot 17')
    await form.getByLabel('Municipal ID').fill('NMC-88412')
    await form.getByLabel('Area (sq ft)').fill('1200')
    await say(page, 'The plot and the house being built are the security: declared for Collateral AI', 1400)
    await page.getByRole('button', { name: 'Declare and open the case' }).click()
    await expect(page.getByTestId('collateral-360')).toBeVisible()
    await page.getByRole('tab', { name: 'Documents' }).click()
    const ecTo = new Date(Date.now() - 10 * 86_400_000).toISOString().slice(0, 10)
    const docs: [string, string, Record<string, unknown>, unknown[]?][] = [
      ['SALE_DEED', 'ahl-sale-deed.png', { owner: NAME, seller: 'Dattu Wagh', surveyNo: 'S.NO.145/3PLOT17', areaSqft: 1200, registrationNo: '3318/2019', registrationDate: '2019-04-22' }],
      ['EC', 'ahl-ec.png', { periodFrom: '2010-01-01', periodTo: ecTo, surveyNo: 'S.NO.145/3PLOT17' }, [{ date: '2019-04-22', type: 'SALE', docNo: '3318/2019', from: 'Dattu Wagh', to: NAME, linkedRef: null }]],
      ['KHATA', 'ahl-khata.png', { owner: NAME, municipalId: 'NMC-88412' }],
      ['PROPERTY_TAX', 'ahl-tax.png', { owner: NAME, municipalId: 'NMC-88412', taxPaidUpTo: '2026-27' }],
      ['VALUATION_REPORT', 'ahl-valuation.png', { marketValue: 2800000, valuer: 'Er. Prakash Deshmukh' }],
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
    await say(page, 'Property papers on file: plot sale deed, EC with no charge, khata, tax receipt, valuation on completion', 2200)
  },
  field: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Property vision' }).click()
    const photos: [string, string][] = [['EXTERIOR_FRONT', 'front'], ['EXTERIOR_SIDE', 'side'], ['INTERIOR_HALL', 'hall'], ['INTERIOR_KITCHEN', 'kitchen'], ['STREET_VIEW', 'street']]
    for (const [i, [view, f]] of photos.entries()) {
      const add = page.locator('.panel', { has: page.getByRole('heading', { name: 'Add a photo' }) })
      await add.locator('select').first().selectOption(view)
      await add.locator('input[type=file]').setInputFiles(fixture(`ahl-photo-${f}.jpg`))
      await add.getByRole('button', { name: 'Upload', exact: true }).click()
      await expect(page.locator('.panel.flush table tbody tr')).toHaveCount(i + 1, { timeout: 30_000 })
    }
    await say(page, 'Step 3 · The field officer photographs the site (GPS checked) and visits the borrower', 1800)
    await openApp(page, NAME)
    await page.locator('[data-action=fieldVisit]').click()
    await page.locator('textarea[name=note]').fill('Plot visited; foundation work started; employer and residence confirmed; neighbours know the family.')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'FIELD')).toHaveText('Done')
  },
  valuer: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Valuation & LTV' }).click()
    await page.getByLabel('Valuer').fill('Er. Prakash Deshmukh (empanelled)')
    await page.getByLabel('Market value (₹)').fill('2800000')
    await page.getByLabel('Realisable value (₹)').fill('2520000')
    await page.getByLabel('Distress value (₹)').fill('2100000')
    await page.locator('[data-action=saveValuation]').click()
    await expect(page.getByText('VAL-', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
    await say(page, 'Step 4 · The valuer values the plot with the finished house at ₹28 lakh', 1800)
  },
  credit: async page => {
    await openCollateral(page)
    await page.getByRole('tab', { name: 'Risk & decision' }).click()
    await page.getByRole('button', { name: 'Run Collateral AI again' }).click()
    await page.waitForTimeout(1500)
    await page.getByRole('tab', { name: '360 summary' }).click()
    await expect(page.getByTestId('collateral-360')).toContainText(/pass/i, { timeout: 20_000 })
    await say(page, 'Collateral AI: PASS on title, EC, photos and valuation', 1800)
  },
  sanctioner: ['cro1', 'CRO'],
  kfsCaption: 'KFS with the EMI; the plan conditions: stage-wise release and pre-EMI interest on what is released',
  disbCaption: 'First tranche: foundation and plinth ₹3,00,000 (less fees) to the borrower; the loan book shows only what is released',
  release: {
    who: ['field1', 'Field officer'],
    caption: 'Weeks later: the field officer inspects the site for the roof-slab stage (needs 50% progress)',
    act: async page => {
      await page.getByLabel('Progress certified (%)').fill('55')
      await page.getByLabel('Inspection note').fill('Roof slab cast on all rooms, columns and beams as per plan; curing in progress.')
      await page.getByRole('button', { name: 'Record inspection' }).click()
      await expect(page.getByRole('status')).toContainText('Inspection recorded')
    },
    then: 'Operations releases the roof-slab stage ₹4,50,000 (the inspector cannot release it: maker-checker). ₹7,50,000 of ₹15 lakh released',
  },
  geo: { latitude: 19.95155, longitude: 73.74805 },
  end: 'Two stages left, each waiting for its inspection. End of the affordable housing walkthrough.',
})
