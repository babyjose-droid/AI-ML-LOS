import type { Page } from '@playwright/test'

/**
 * Application 360 opens one stage at a time (stepper, as in prototype v3.1): its section tabs show for the chosen stage only.
 * Opens the stage that holds the section (if its tab is not on screen) and then the section's tab.
 */
export async function openSection(page: Page, name: string) {
  const tab = page.getByRole('tab', { name, exact: true })
  await page.locator('.phases').waitFor()
  if (!(await tab.isVisible())) await page.locator(`.phases button[data-sections*="|${name}|"]`).click()
  await tab.click()
}
