import { expect } from '@playwright/test'
import { openApp, say, state } from './asset-journey'
import { day } from './pdf'
import { planJourney } from './plan-journey'

// Farm & allied: crop cultivation on 4 acres, 7/12 read by AI, scale of finance, half-yearly repayment after the harvests.
planJourney({
  title: 'Farm & allied loan journey (recorded): 7/12 land record AI, scale of finance, crop insurance, field visit, seasonal KFS, disbursement',
  intro: 'Step 1 · Sales captures a farm loan for onion and soybean cultivation with drip irrigation',
  product: 'AGRI', name: 'Vitthal Shelke', pan: 'VITPS2284S', mobile: '9876500833', dob: '1975-06-01', gender: 'MALE', city: 'Niphad', pincode: '422209',
  address: 'At post Pimpalgaon Baswant', business: 'Shelke Farm', industry: 'AGRI', vintage: '20', income: '60000', expenses: '20000',
  account: '50100833445566', ifsc: 'HDFC0001234', amount: '240000', tenure: '24', purpose: 'Onion and soybean cultivation, drip irrigation',
  amountCaption: '₹2,40,000 for 24 months, repaid after each harvest',
  kyc: [['PAN', 'agri-vitthal-pan.png'], ['AADHAAR', 'agri-vitthal-aadhaar.png']],
  planTitle: 'Farm & activity', disbTitle: 'Disbursement',
  docs: [
    { type: 'LAND_RECORD_712', file: '7-12-extract.pdf', title: '7/12 EXTRACT (SATBARA)', issuer: 'Talathi, Pimpalgaon Baswant (fictional specimen)',
      lines: ['Village: Pimpalgaon Baswant', 'Taluka: Niphad', 'Gat No: 418/2', 'Occupant: Vitthal Shelke', 'Area: 1.62 ha', 'Crops: Onion, Soybean', 'Encumbrance: Nil'] },
    { type: 'INSURANCE', file: 'pmfby-policy.pdf', title: 'CROP INSURANCE (PMFBY)', issuer: 'Fictional General Insurance Ltd (specimen)',
      lines: ['Insurer: Fictional General Insurance Ltd', 'Scheme: PMFBY Kharif 2026', 'Policy No: PMFBY/26/KH/77812', 'Sum insured: 2,60,000', 'Valid till: ' + day(150)] },
  ],
  fields: [['activity', 'CROP'], ['scalePerUnit', '65000'], ['ownContribution', '30000'], ['harvestMonths', 'NOV, MAY']],
  planCaption: '7/12 read by AI: occupant, Gat number, village, area 1.62 ha = 4 acres, crops; crop insurance read too',
  checksCaption: 'Plan checks: land in the borrower’s name, within ₹65,000 per acre × 4 acres, 11% margin, repayment after harvest, insurance, area matches',
  field: async page => {
    await openApp(page, 'Vitthal Shelke')
    await say(page, 'Step 3 · The field officer visits the farm: land, crop and water source', 1600)
    await page.locator('[data-action=fieldVisit]').click()
    await page.locator('textarea[name=note]').fill('Farm at Gat 418/2 visited: well and drip lines, onion nursery ready, soybean sown on 2 acres.')
    await page.getByRole('button', { name: 'Confirm' }).click()
    await expect(state(page, 'FIELD')).toHaveText('Done')
  },
  sanctioner: ['cm1', 'Credit manager'],
  kfsCaption: 'Seasonal KFS: 4 half-yearly instalments after the harvests, each principal plus that half-year’s interest; APR from those dates',
  disbCaption: 'Disbursed to the farmer’s account; the loan book follows the half-yearly schedule',
  end: 'Repayment follows the harvests, not the calendar month. End of the farm loan walkthrough.',
})
