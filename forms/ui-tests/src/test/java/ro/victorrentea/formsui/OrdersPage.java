package ro.victorrentea.formsui;

import ro.victorrentea.formsui.pw.FormsApp;
import ro.victorrentea.formsui.pw.Locator;

import java.util.List;

/** Page Object of the ORDERS form: what a clerk can see and do on it, in clerk words. */
public class OrdersPage {
  static final List<String> COLUMNS = List.of("Id", "Cust", "Customer", "Product", "Qty", "Unit Price", "Disc %", "Total", "Status");
  private final FormsApp app;

  public OrdersPage(FormsApp app) {
    this.app = app;
  }

  /** One line of the multi-record block, numbered from 1 like the "Record: n/m" indicator. */
  public class OrderLine {
    private final int index;

    OrderLine(int number) {
      this.index = number - 1;
    }

    /** A cell by its column prompt: "Id", "Customer", "Product", "Qty", "Total", "Status"... */
    public Locator column(String name) {
      if (!COLUMNS.contains(name)) throw new IllegalArgumentException("No column " + name + " in " + COLUMNS);
      return app.cell(index, COLUMNS.indexOf(name), name);
    }

    public Locator id() { return column("Id"); }
    public Locator customer() { return column("Customer"); }
    public Locator product() { return column("Product"); }
    public Locator quantity() { return column("Qty"); }
    public Locator unitPrice() { return column("Unit Price"); }
    public Locator discount() { return column("Disc %"); }
    public Locator total() { return column("Total"); }
    public Locator status() { return column("Status"); }
  }

  public OrderLine line(int number) {
    return new OrderLine(number);
  }

  /** The Forms message line at the bottom of the window. */
  public Locator message() {
    return app.messageLine();
  }

  /** "Record: n/m": which order the cursor is on, out of how many. */
  public Locator recordIndicator() {
    return app.recordIndicator();
  }

  public int orderCount() {
    return (int) app.snapshot().rows().stream().filter(r -> !r.get(0).isBlank()).count();
  }

  /** Moves the cursor to the given line with the arrow keys, as a Forms user does. */
  public OrdersPage goToLine(int number) {
    int target = number - 1;
    int current = app.snapshot().cursorRow();
    for (; current < target; current++) app.press("Down");
    for (; current > target; current--) app.press("Up");
    return this;
  }

  /** Arrow down past the last order to a blank line and key in a new order (not saved yet). */
  public OrdersPage enterNewOrder(int customerId, String product, String quantity, String unitPrice) {
    goToLine(orderCount() + 1);
    app.type(String.valueOf(customerId)).press("Tab")
        .type(product).press("Tab")
        .type(quantity).press("Tab")
        .type(unitPrice);
    return this;
  }

  /** Commit, Forms' Save: Ctrl+S. */
  public OrdersPage save() {
    app.press("Control+S");
    return this;
  }

  public OrdersPage ship() {
    app.button("Ship").click();
    return this;
  }

  public OrdersPage cancelOrder() {
    app.button("Cancel Order").click();
    return this;
  }
}
