import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { test as base, expect, Locator, Page } from '@playwright/test';

const STACK = process.env.STACK ?? 'ui';
const STACK_SH = path.resolve(__dirname, '../../stack.sh');

/**
 * Every test starts from the same known data (see db/04_demo_data.sql, but the tests only rely
 * on what a user would see on screen):
 *   Customers: Acme Corp (limit 5000, active), Globex (limit 1000, active), Initech (limit 2000, inactive)
 *   Orders:    #1 Acme Widget 5 x 20 NEW, #2 Acme Gadget 12 x 50 SHIPPED, #3 Globex Sprocket 3 x 100 CANCELLED
 */
export const test = base.extend<{ freshData: void }>({
  freshData: [
    async ({}, use) => {
      execFileSync(STACK_SH, ['seed', STACK], { stdio: 'pipe' });
      await use();
    },
    { auto: true },
  ],
});

export { expect };

// ---- what the user sees ----

/** The status bar at the bottom of every screen. */
export const statusBar = (page: Page) => page.getByRole('status');

export const SAVED = 'FRM-40400: Transaction complete: 1 records applied and saved.';

export async function expectInfo(page: Page, text: string | RegExp) {
  await expect(statusBar(page)).toContainText(text);
  await expect(statusBar(page).locator('.info')).toBeVisible();
}

export async function expectError(page: Page, text: string | RegExp) {
  await expect(statusBar(page)).toContainText(text);
  await expect(statusBar(page).locator('.error')).toBeVisible();
}

/** The data grid on the current screen. */
export const grid = (page: Page) => page.getByRole('table');

/** Grid body rows that contain the given text. */
export const gridRows = (page: Page, text?: string | RegExp): Locator => {
  const rows = grid(page).locator('tbody').getByRole('row');
  return text === undefined ? rows : rows.filter({ hasText: text });
};

// ---- navigation, like using the menu ----

export async function openMenu(page: Page, item: 'Orders' | 'Customers' | 'Audit Log') {
  if (page.url() === 'about:blank') await page.goto('/');
  await page.getByRole('navigation').getByRole('link', { name: item, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: item })).toBeVisible();
}

/** From the Orders grid, open the order whose row shows the given product. */
export async function openOrder(page: Page, product: string) {
  await openMenu(page, 'Orders');
  await gridRows(page, product).getByRole('link').click();
  await expect(page.getByRole('heading', { level: 1, name: /^Order \d+$/ })).toBeVisible();
}

export type NewOrder = { customer: string; product: string; quantity: string; unitPrice: string };

export async function enterNewOrder(page: Page, order: NewOrder) {
  await openMenu(page, 'Orders');
  await page.getByRole('link', { name: 'New Order' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'New Order' })).toBeVisible();
  await page.getByLabel('Customer').selectOption({ label: order.customer });
  await page.getByLabel('Product').fill(order.product);
  await page.getByLabel('Quantity').fill(order.quantity);
  await page.getByLabel('Unit Price').fill(order.unitPrice);
  await page.getByRole('button', { name: 'Save' }).click();
}

export async function queryOrders(page: Page, filter: { customer?: string; status?: string; product?: string }) {
  await openMenu(page, 'Orders');
  if (filter.customer) await page.getByLabel('Customer').selectOption({ label: filter.customer });
  if (filter.status) await page.getByLabel('Status').selectOption(filter.status);
  if (filter.product) await page.getByLabel('Product').fill(filter.product);
  await page.getByRole('button', { name: 'Execute Query' }).click();
}

/** The read-only fields the database computes, as shown on the order form. */
export async function expectComputed(page: Page, expected: { discount: string; total: string; status: string }) {
  for (const [label, value] of [
    ['Discount %', expected.discount],
    ['Total', expected.total],
    ['Status', expected.status],
  ] as const) {
    const field = page.getByLabel(label, { exact: true });
    await expect(field).toHaveValue(value);
    await expect(field).not.toBeEditable();
  }
}
