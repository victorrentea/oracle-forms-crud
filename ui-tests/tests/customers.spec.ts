import { test, expect, SAVED, expectInfo, expectError, gridRows, openMenu } from './fixtures';

test.describe('Customers', () => {

  test('a second customer with an existing name is refused', async ({ page }) => {
    await openMenu(page, 'Customers');
    await page.getByRole('link', { name: 'New Customer' }).click();
    await page.getByLabel('Name').fill('Globex');
    await page.getByLabel('Credit Limit').fill('500');
    await page.getByRole('button', { name: 'Save' }).click();

    await expectError(page, 'Customer "Globex" already exists');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('New Customer');

    await openMenu(page, 'Customers');
    await expect(gridRows(page, 'Globex')).toHaveCount(1);
  });

  test('a customer who has orders cannot be deleted', async ({ page }) => {
    await openMenu(page, 'Customers');
    await gridRows(page, 'Acme Corp').getByRole('link').click();

    await page.getByRole('button', { name: 'Delete' }).click();

    await expectError(page, 'Customer has 2 order(s) and cannot be deleted');
    await openMenu(page, 'Customers');
    await expect(gridRows(page, 'Acme Corp')).toHaveCount(1);
  });

  test('a new customer without orders can be created and then deleted', async ({ page }) => {
    await openMenu(page, 'Customers');
    await page.getByRole('link', { name: 'New Customer' }).click();
    await page.getByLabel('Name').fill('Umbrella');
    await page.getByLabel('Credit Limit').fill('750');
    await page.getByRole('button', { name: 'Save' }).click();

    await expectInfo(page, SAVED);
    await expect(page.getByLabel('Active')).toBeChecked();

    await page.getByRole('button', { name: 'Delete' }).click();

    await expectInfo(page, SAVED);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Customers');
    await expect(gridRows(page, 'Umbrella')).toHaveCount(0);
  });
});
