package ro.victorrentea.formsui;

import org.junit.jupiter.api.Test;
import ro.victorrentea.formsui.pw.FormsApp;
import ro.victorrentea.formsui.pw.FormsTest;

import static ro.victorrentea.formsui.pw.Expect.expect;

/**
 * The Orders screen of the real Oracle Forms application, driven like a clerk would: from the
 * keyboard, reading the lines and the message line. Nothing here knows there is PL/SQL behind it.
 * Each test gets a fresh client on fresh data; its trace lands in target/forms-report/.
 */
@FormsTest
class OrdersFormTest {
  static final String SAVED = "FRM-40400: Transaction complete: 1 records applied and saved.";

  @Test
  void theFormOpensWithTheOrdersAlreadyQueried(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    expect(orders.line(1).product()).toHaveText("Widget");
    expect(orders.line(2).status()).toHaveText("SHIPPED");
    expect(orders.line(3).customer()).toHaveText("Globex");
    expect(orders.message()).toHaveText("Record: 1/3");
  }

  @Test
  void aClerkEntersANewOrderAndSeesItsTotal(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    orders.enterNewOrder(1, "Bolt", "12", "10").save();

    expect(orders.message()).toContainText(SAVED);
    expect(orders.line(4).id()).toHaveText("100");
    expect(orders.line(4).customer()).toHaveText("Acme Corp");
    expect(orders.line(4).discount()).toHaveText("5");
    expect(orders.line(4).total()).toHaveText("114");
    expect(orders.line(4).status()).toHaveText("NEW");
  }

  @Test
  void anOrderOverTheCreditLimitIsRefused(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    orders.enterNewOrder(2, "Gold", "1", "5000").save();

    expect(orders.message()).toContainText("ORA-20002: Credit limit exceeded");
  }

  @Test
  void aQuantityOfZeroIsRejected(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    orders.enterNewOrder(1, "Bolt", "0", "10").save();

    expect(orders.message()).toContainText("ORA-20001: Quantity must be a positive whole number");
  }

  @Test
  void aNewOrderCanBeShipped(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    orders.goToLine(1).ship();

    expect(orders.line(1).status()).toHaveText("SHIPPED");
    expect(orders.message()).toContainText(SAVED);
  }

  @Test
  void aShippedOrderCannotBeCancelled(FormsApp app) {
    OrdersPage orders = new OrdersPage(app);

    orders.goToLine(2).cancelOrder();

    expect(orders.message()).toContainText("ORA-20004: Only NEW orders can be cancelled (order is SHIPPED)");
    expect(orders.line(2).status()).toHaveText("SHIPPED");
  }
}
