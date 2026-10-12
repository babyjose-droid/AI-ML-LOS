/**
 * Screen tour: every page for the roles that use it, on desktop and phone widths.
 * Fails on browser console errors, failed API calls (5xx) or sideways scrolling; saves a screenshot of each page.
 */
import { expect, test, type Page } from '@playwright/test'

const PAGES: [string, string, string[]][] = [
  ['my-work', '/my-work', ['sales1', 'ops1', 'cm1', 'fraud1']],
  ['pipeline', '/applications', ['ops1']],
  ['new-application', '/applications/new', ['sales1']],
  ['integration-logs', '/integrations', ['ops1']],
  ['audit', '/audit', ['comp1']],
  ['state-machine', '/state-machine', ['ops1']],
  ['branches', '/admin/branches', ['admin']],
  ['users', '/admin/users', ['admin']],
  ['products', '/admin/products', ['admin']],
  ['policy', '/admin/policy', ['admin']],
]
const TABS = ['Overview', 'Documents', 'Data', 'Decision', 'Credit memo', 'Sanction & KFS', 'Disbursement', 'History', 'Integrations']

async function login(page: Page, user: string) {
  await page.goto('/')
  await page.evaluate(() => { try { localStorage.clear() } catch { /* ignore */ } })
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill('Rhythm@123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'My work' })).toBeVisible()
}

function watch(page: Page) {
  const problems: string[] = []
  page.on('console', m => { if (m.type() === 'error' && !/favicon|fonts\.g/.test(m.text())) problems.push('console: ' + m.text()) })
  page.on('pageerror', e => problems.push('page error: ' + e.message))
  page.on('response', r => { if (r.status() >= 500) problems.push(`HTTP ${r.status()} ${r.url()}`) })
  return problems
}

async function noSidewaysScroll(page: Page, what: string) {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(over, `${what} scrolls sideways by ${over}px`).toBeLessThanOrEqual(1)
}

for (const width of [1366, 390]) {
  test(`screen tour at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: width > 500 ? 900 : 844 })
    const problems = watch(page)
    const done = new Set<string>()
    for (const [name, url, users] of PAGES) {
      for (const user of users) {
        if (!done.has(user)) { await login(page, user); done.clear(); done.add(user) }
        await page.goto(url)
        await page.waitForLoadState('networkidle')
        await expect(page.locator('h2').first()).toBeVisible()
        await noSidewaysScroll(page, `${name} as ${user}`)
        await page.screenshot({ path: `screens/${width}/${name}-${user}.png`, fullPage: true })
      }
    }
    // Application 360: every tab, on the most complete demo case (Priya, disbursed)
    await login(page, 'ops1')
    await page.goto('/applications')
    await page.getByPlaceholder('Search name, PAN or application no.').fill('Priya Nair')
    await page.getByRole('cell', { name: 'Priya Nair', exact: true }).first().click()
    for (const tab of TABS) {
      await page.getByRole('tab', { name: tab }).click()
      await page.waitForLoadState('networkidle')
      await noSidewaysScroll(page, `Application 360 / ${tab}`)
      await page.screenshot({ path: `screens/${width}/app360-${tab.replace(/[^A-Za-z]+/g, '-').toLowerCase()}.png`, fullPage: true })
    }
    expect(problems, problems.join('\n')).toEqual([])
  })
}
