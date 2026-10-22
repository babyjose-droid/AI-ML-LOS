import { expect, test } from '@playwright/test'
import { say } from './asset-journey'
import { back, captions, decide, login, logout, newApplication, openApp, phone, runChecks, sanctionToDisbursement } from './mobile-helpers'

/**
 * Recorded group loan (JLG) on the mobile app: the sales officer captures the member's loan and her household; operations
 * runs the checks; the field officer records the group formation and training (CGT) on the phone; the credit officer
 * records the group recognition test (GRT) and the decision; sanction, KFS and disbursement. Fictional member and SPECIMEN
 * documents.
 */
phone({ latitude: 19.8462, longitude: 73.9976 })   // Sinnar (fictional village meeting)
const NAME = 'Savita Jadhav'

test('Mobile group loan (recorded): household, CGT by the field officer, GRT by credit, decision, sanction, KFS and disbursement', async ({ page }) => {
  test.setTimeout(15 * 60_000)
  await captions(page)

  await login(page, 'sales1', 'Sales officer')
  await say(page, 'Group loan on the phone: a woman dairy farmer in a joint liability group', 2000)
  await newApplication(page, {
    product: 'JLG income-generation loan', amount: '40000', tenure: '12', purpose: 'Buy a second milch cow', name: NAME, pan: 'SAVPJ6610B', mobile: '9876531802',
    dob: '1987-06-03', gender: 'Female', address: 'House 8, Shivaji Nagar', city: 'Sinnar', pincode: '422103', business: 'Dairy (2 cows)', industry: 'Dairy',
    vintage: '6', income: '15000', expenses: '9000', account: '50100318021223', ifsc: 'HDFC0001234',
    docs: [['PAN', 'mob-savita-pan.png'], ['Aadhaar', 'mob-savita-aadhaar.png']],
    captions: { loan: '₹40,000 for 12 months, repaid fortnightly', income: 'Dairy income ₹15,000 a month: the dairy scorecard is used' },
  })
  await page.getByTestId('go-household').click()
  await say(page, 'In microfinance the household repays: its members, earners, expenses and loans', 1800)
  await page.getByTestId('edit-household').click()
  await page.getByTestId('hh-members').fill('4')
  await page.getByTestId('hh-expenses').fill('9000')
  await page.getByTestId('hh-emi').fill('1500')
  await page.getByLabel('Member 1: name').fill(NAME)
  await page.getByLabel('Member 1: occupation').fill('Dairy')
  await page.getByLabel('Member 1: income / month (₹)').fill('15000')
  await page.getByTestId('add-earner').click()
  await page.getByLabel('Member 2: name').fill('Dnyaneshwar Jadhav')
  await page.getByTestId('hh-relation-2').getByRole('radio', { name: 'Spouse', exact: true }).click()
  await page.getByLabel('Member 2: occupation').fill('Farm labour')
  await page.getByLabel('Member 2: income / month (₹)').fill('9000')
  await page.getByTestId('save-household').click()
  await expect(page.getByTestId('mfi')).toContainText('qualifies')
  await say(page, 'Household income ₹2.88 lakh a year: qualifies as microfinance; repayments must stay within 50% of income', 2600)
  await back(page)
  await logout(page)

  await runChecks(page, NAME, 'Operations runs the automated checks: documents, KYC, bank data, bureau, fraud and group links')

  await login(page, 'field1', 'Field officer')
  await openApp(page, NAME)
  await page.getByTestId('go-group').click()
  await say(page, 'The field officer forms the group and holds the compulsory group training (CGT), recorded on the phone', 1800)
  await page.getByTestId('record-cgt').click()
  await page.getByTestId('stage-groupName').fill('Ahilyabai Mahila Gat')
  await page.getByTestId('stage-members').fill('5')
  await page.getByTestId('stage-sessionsHeld').fill('3')
  await page.getByTestId('stage-note').fill('Five women from Shivaji Nagar formed the group; three sessions on joint liability and repayment')
  await page.getByTestId('confirm').click()
  await expect(page.getByTestId('group-status-cgt')).toContainText('Pass')
  await say(page, 'CGT recorded: group name, 5 members, 3 training sessions', 1400)
  await back(page)
  await logout(page)

  await login(page, 'co1', 'Credit officer')
  await openApp(page, NAME)
  await page.getByTestId('go-group').click()
  await say(page, 'The credit officer runs the group recognition test (GRT)', 1400)
  await page.getByTestId('record-grt').click()
  await page.getByTestId('stage-membersPresent').fill('5')
  await page.getByTestId('stage-note').fill('All five members present; each explained the loan, the fortnightly repayment and joint liability')
  await page.getByTestId('confirm').click()
  await expect(page.getByTestId('group-status-grt')).toContainText('Pass')
  await back(page)
  await logout(page)

  await decide(page, NAME, 'Decision on the dairy scorecard with the household affordability rule')
  await sanctionToDisbursement(page, NAME, {
    note: 'Group trained and tested; household within the microfinance cap; sanction as recommended',
    kfs: 'KFS with the fortnightly schedule and the all-in APR',
    end: 'Disbursed to her own account. End of the mobile group loan walkthrough',
  })
})
