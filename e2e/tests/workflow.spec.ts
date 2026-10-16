import { expect, test, type Page } from '@playwright/test'

// Workflow designer: a credit manager changes the gold loan workflow, the CRO publishes it; then the JLG demo
// application (Lakshmi Devi) waits at group formation until the field officer and credit officer record CGT and GRT.

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Sign out' }).click()
}

test('workflow designer: edit, validate, send for approval, CRO publishes', async ({ page }) => {
  await login(page, 'cm1')
  await page.goto('/admin/workflows?product=GL')
  await expect(page.getByRole('heading', { name: 'Workflow designer' })).toBeVisible()
  await expect(page.locator('[data-wf-status=PUBLISHED]')).toBeVisible()
  await page.locator('[data-wf=new-draft]').click()
  await expect(page.locator('[data-wf-status=DRAFT]')).toBeVisible()

  // a mandatory stage cannot be skipped: the check reacts while editing
  await page.locator('[data-wf-stage=kyc]').click()
  await expect(page.locator('input[name=stageSkip]')).toBeDisabled()

  await page.locator('[data-wf-stage=gold]').click()
  await page.locator('input[name=stageSla]').fill('0')
  await expect(page.locator('[data-wf-issues]')).toContainText('SLA must be between')
  await page.locator('input[name=stageSla]').fill('2')
  await page.locator('select[name=stageOnFailure]').selectOption('ESCALATE')
  await expect(page.locator('[data-wf-valid]')).toBeVisible()

  await page.locator('select[name=addStage]').selectOption('legal')
  await page.locator('[data-wf=add-stage]').click()
  await expect(page.locator('[data-wf-stage=legal]')).toBeVisible()
  await page.locator('input[name=stageSkip]').fill('amount <= 2L')
  await page.locator('textarea[name=wfNotes]').fill('Gold appraisal SLA 2 h; legal check above 2 lakh')
  await page.locator('[data-wf=save]').click()
  await expect(page.locator('[data-wf-changes]')).toContainText('Added Legal')
  await page.locator('[data-wf=submit]').click()
  await expect(page.locator('[data-wf-status=PENDING_APPROVAL]')).toBeVisible()
  await logout(page)

  await login(page, 'cro1')
  await expect(page.getByText('Workflow GL v2 waiting for your approval')).toBeVisible()
  await page.getByText('Workflow GL v2 waiting for your approval').click()
  await page.locator('[data-wf-product=GL]').click()
  await page.locator('textarea[name=wfDecisionNote]').fill('Legal check for larger gold loans agreed')
  await page.locator('[data-wf=approve]').click()
  await expect(page.locator('[data-wf-version="2"][data-wf-status=PUBLISHED]')).toBeVisible()
})

test('JLG application waits for CGT and GRT recorded by the field and credit officers', async ({ page }) => {
  await login(page, 'field1')
  await expect(page.getByText('Group formation (CGT)').first()).toBeVisible()
  await page.locator('[data-task]', { hasText: 'Lakshmi Devi' }).first().getByRole('button', { name: 'Open task' }).click()
  await expect(page.locator('[data-stage=cgt]')).toBeVisible()
  await expect(page.locator('[data-stage=cgt]')).toHaveAttribute('data-stage-status', /ACTIVE|PENDING/)
  await page.locator('[data-stage-mine]').click()
  await page.locator('[data-stage-complete=cgt]').click()
  await page.locator('input[name=stage_groupName]').fill('Meenakshi SHG')
  await page.locator('input[name=stage_members]').fill('5')
  await page.locator('input[name=stage_sessionsHeld]').fill('3')
  await page.locator('textarea[name=stageNote]').fill('Five women formed the group, three training sessions held')
  await page.locator('[data-stage-save]').click()
  await expect(page.locator('[data-stage=cgt]')).toHaveAttribute('data-stage-status', 'DONE')
  const url = page.url()
  await logout(page)

  await login(page, 'co1')
  await page.goto(url)
  await page.locator('[data-stage-mine]').click()
  await page.locator('[data-stage-complete=grt]').click()
  await page.locator('input[name=stage_membersPresent]').fill('5')
  await page.locator('textarea[name=stageNote]').fill('All members explained the loan and repayment schedule')
  await page.locator('[data-stage-save]').click()
  await expect(page.locator('[data-stage=grt]')).toHaveAttribute('data-stage-status', 'DONE')
  await expect(page.getByText('Workflow stage "Group')).toHaveCount(0)
})
