import {
  test, expect, SAVED, expectInfo, expectError, gridRows, openMenu, openOrder,
  enterNewOrder, queryOrders, expectComputed,
} from './fixtures';

test.describe('Orders', () => {

  test('a clerk enters a new order and sees its total computed', async ({ page }) => {
    await enterNewOrder(page, { customer: 'Acme Corp', product: 'Bolt', quantity: '4', unitPrice: '25' });

    await expectInfo(page, SAVED);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/^Order \d+$/);
    await expectComputed(page, { discount: '0', total: '100.00', status: 'NEW' });

    await openMenu(page, 'Orders');
    await expect(gridRows(page, 'Bolt')).toContainText(['Acme Corp']);
  });

  for (const { quantity, unitPrice, discount, total } of [
    { quantity: '9', unitPrice: '10', discount: '0', total: '90.00' },
    { quantity: '10', unitPrice: '10', discount: '5', total: '95.00' },
    { quantity: '49', unitPrice: '10', discount: '5', total: '465.50' },
    { quantity: '50', unitPrice: '10', discount: '10', total: '450.00' },
  ]) {
    test(`ordering ${quantity} items earns a ${discount}% volume discount`, async ({ page }) => {
      await enterNewOrder(page, { customer: 'Acme Corp', product: 'Nut', quantity, unitPrice });

      await expectInfo(page, SAVED);
      await expectComputed(page, { discount, total, status: 'NEW' });
    });
  }

  test('an order over the customer credit limit is refused and not saved', async ({ page }) => {
    // Globex may owe at most 1000.00; 11 x 100 with 5% discount = 1045.00
    await enterNewOrder(page, { customer: 'Globex', product: 'Flywheel', quantity: '11', unitPrice: '100' });

    await expectError(page, 'Credit limit exceeded: open orders would total 1045.00 but limit is 1000.00');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('New Order');

    await queryOrders(page, { customer: 'Globex' });
    await expect(gridRows(page)).toHaveCount(1);
    await expect(gridRows(page, 'Flywheel')).toHaveCount(0);
  });

  test('an inactive customer cannot be picked for a new order', async ({ page }) => {
    await openMenu(page, 'Orders');
    // still searchable in query mode...
    await expect(page.getByLabel('Customer').getByRole('option', { name: 'Initech' })).toHaveCount(1);

    await page.getByRole('link', { name: 'New Order' }).click();
    const customers = page.getByLabel('Customer').getByRole('option');
    // ...but not offered when entering a new order
    await expect(customers).toHaveText(['', 'Acme Corp', 'Globex']);
  });

  test('a shipped order can no longer be cancelled', async ({ page }) => {
    await openOrder(page, 'Widget');

    await page.getByRole('button', { name: 'Ship' }).click();
    await expectInfo(page, SAVED);
    await expect(page.getByLabel('Status', { exact: true })).toHaveValue('SHIPPED');

    await page.getByRole('button', { name: 'Cancel Order' }).click();
    await expectError(page, 'Only NEW orders can be cancelled (order is SHIPPED)');
    await expect(page.getByLabel('Status', { exact: true })).toHaveValue('SHIPPED');
  });

  test('a shipped order cannot be deleted', async ({ page }) => {
    await openOrder(page, 'Gadget');

    await page.getByRole('button', { name: 'Delete' }).click();

    await expectError(page, 'Shipped orders cannot be deleted');
    await openMenu(page, 'Orders');
    await expect(gridRows(page, 'Gadget')).toHaveCount(1);
  });

  test('a NEW order can be deleted and disappears from the grid', async ({ page }) => {
    await openOrder(page, 'Widget');

    await page.getByRole('button', { name: 'Delete' }).click();

    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Orders');
    await expectInfo(page, SAVED);
    await expect(gridRows(page, 'Widget')).toHaveCount(0);
    await expect(gridRows(page)).toHaveCount(2);
  });

  test('the orders grid can be queried by status, customer and product', async ({ page }) => {
    await openMenu(page, 'Orders');
    await expect(gridRows(page)).toHaveCount(3);
    await expect(page.getByText('3 record(s) retrieved')).toBeVisible();

    await queryOrders(page, { status: 'SHIPPED' });
    await expect(gridRows(page)).toHaveCount(1);
    await expect(gridRows(page)).toContainText(['Gadget']);

    await queryOrders(page, { customer: 'Globex' });
    await expect(gridRows(page)).toHaveCount(1);
    await expect(gridRows(page)).toContainText(['Sprocket']);

    await queryOrders(page, { product: 'wid' }); // partial, any case
    await expect(gridRows(page)).toHaveCount(1);
    await expect(gridRows(page)).toContainText(['Widget']);

    await queryOrders(page, { customer: 'Acme Corp', status: 'CANCELLED' });
    await expect(gridRows(page)).toHaveCount(0);
    await expect(page.getByText('0 record(s) retrieved')).toBeVisible();
  });

  test('changing the quantity of a NEW order recomputes its total', async ({ page }) => {
    await openOrder(page, 'Widget');
    await expect(page.getByLabel('Customer')).toHaveValue('Acme Corp');
    await expect(page.getByLabel('Customer')).not.toBeEditable(); // the customer is fixed once ordered
    await expectComputed(page, { discount: '0', total: '100.00', status: 'NEW' });

    await page.getByLabel('Quantity').fill('10');
    await page.getByRole('button', { name: 'Save' }).click();

    await expectInfo(page, SAVED);
    await expect(page.getByLabel('Quantity')).toHaveValue('10');
    await expectComputed(page, { discount: '5', total: '190.00', status: 'NEW' });
  });

  test('a non-numeric quantity is rejected before anything is saved', async ({ page }) => {
    await enterNewOrder(page, { customer: 'Acme Corp', product: 'Bolt', quantity: 'ten', unitPrice: '25' });

    await expectError(page, 'FRM-50016: Legal characters are 0-9 - + E . (field Quantity)');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('New Order');
    // what the user typed is kept on screen for correction
    await expect(page.getByLabel('Quantity')).toHaveValue('ten');
    await expect(page.getByLabel('Product')).toHaveValue('Bolt');

    await openMenu(page, 'Orders');
    await expect(gridRows(page, 'Bolt')).toHaveCount(0);
  });
});
