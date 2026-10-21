import { test, expect, SAVED, expectInfo, gridRows, openMenu, enterNewOrder } from './fixtures';

test.describe('Audit Log', () => {

  test('every change made through the screens leaves a trail in the Audit Log', async ({ page }) => {
    await openMenu(page, 'Audit Log');
    await expect(gridRows(page)).toHaveCount(0); // a fresh start has no history

    await enterNewOrder(page, { customer: 'Acme Corp', product: 'Bolt', quantity: '4', unitPrice: '25' });
    await expectInfo(page, SAVED);
    const orderId = (await page.getByRole('heading', { level: 1 }).textContent())!.replace('Order ', '');

    await page.getByRole('button', { name: 'Ship' }).click();
    await expectInfo(page, SAVED);

    await openMenu(page, 'Audit Log');
    const forThisOrder = gridRows(page).filter({
      has: page.getByRole('cell', { name: 'ORDERS', exact: true }),
    }).filter({
      has: page.getByRole('cell', { name: orderId, exact: true }),
    });
    await expect(forThisOrder).toHaveCount(2);

    const inserted = forThisOrder.filter({ has: page.getByRole('cell', { name: 'INSERT', exact: true }) });
    await expect(inserted).toContainText('product=Bolt;quantity=4;unit_price=25.00;total=100.00;status=NEW');

    const shipped = forThisOrder.filter({ has: page.getByRole('cell', { name: 'UPDATE', exact: true }) });
    await expect(shipped).toContainText('status=NEW');     // old values
    await expect(shipped).toContainText('status=SHIPPED'); // new values
  });
});
