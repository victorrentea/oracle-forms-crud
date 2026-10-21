package ro.victorrentea.blackbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The order rules, as a user experiences them through the Orders screens. */
class OrderRulesTest {
  static final String SAVED = "FRM-40400: Transaction complete: 1 records applied and saved.";
  Ui ui = new Ui();

  @BeforeEach
  void knownData() {
    Stack.resetData();
  }

  Screen newOrder(String customer, String product, String quantity, String unitPrice) {
    Screen form = ui.click(ui.menu("Orders"), "New Order");
    return ui.on(form)
        .fill("Customer", customer)
        .fill("Product", product)
        .fill("Quantity", quantity)
        .fill("Unit Price", unitPrice)
        .press("Save");
  }

  Screen order(String id) {
    return ui.click(ui.menu("Orders"), id);
  }

  @Test
  void savingAnOrderShowsItsComputedTotal() {
    Screen saved = newOrder("Acme Corp", "Bolt", "3", "2.50");

    assertThat(saved.message()).isEqualTo(SAVED);
    assertThat(saved.field("Total")).isEqualTo("7.50");
    assertThat(saved.field("Discount %")).isEqualTo("0");
    assertThat(saved.field("Status")).isEqualTo("NEW");
  }

  @ParameterizedTest(name = "{0} items at 10.00 → {1}% off, total {2}")
  @CsvSource({
      "9,  0,  90.00",
      "10, 5,  95.00",
      "49, 5,  465.50",
      "50, 10, 450.00",
  })
  void volumeDiscount(String quantity, String discount, String total) {
    Screen saved = newOrder("Acme Corp", "Bolt", quantity, "10");

    assertThat(saved.field("Discount %")).isEqualTo(discount);
    assertThat(saved.field("Total")).isEqualTo(total);
  }

  @Test
  void anOrderOverTheCreditLimitIsRefusedAndNotSaved() {
    Screen refused = newOrder("Globex", "Gold", "1", "1000.01");

    assertThat(refused.isError()).isTrue();
    assertThat(refused.message()).isEqualTo(
        "ORA-20002: Credit limit exceeded: open orders would total 1000.01 but limit is 1000.00");
    assertThat(refused.field("Product")).as("what the user typed is kept").isEqualTo("Gold");
    assertThat(ui.menu("Orders").grid()).extracting(r -> r.get("Product")).doesNotContain("Gold");
  }

  @Test
  void cancelledOrdersDoNotCountTowardsTheCreditLimit() {
    // Globex has only a CANCELLED order of 300.00 and a limit of 1000.00
    assertThat(newOrder("Globex", "Gold", "1", "1000").message()).isEqualTo(SAVED);
  }

  @Test
  void inactiveCustomersCannotBePickedForANewOrder() {
    Screen form = ui.click(ui.menu("Orders"), "New Order");

    assertThat(form.options("Customer")).containsExactly("Acme Corp", "Globex");
  }

  @ParameterizedTest(name = "quantity ''{0}'', price ''{1}'' → {2}")
  @CsvSource(delimiter = '|', textBlock = """
      0    | 10  | ORA-20001: Quantity must be a positive whole number
      -1   | 10  | ORA-20001: Quantity must be a positive whole number
      1.5  | 10  | ORA-20001: Quantity must be a positive whole number
      ''   | 10  | ORA-20001: Quantity must be a positive whole number
      abc  | 10  | FRM-50016: Legal characters are 0-9 - + E . (field Quantity)
      1    | 0   | ORA-20001: Unit price must be positive
      1    | x1  | FRM-50016: Legal characters are 0-9 - + E . (field Unit Price)
      """)
  void invalidInputIsRejected(String quantity, String unitPrice, String message) {
    Screen refused = newOrder("Acme Corp", "Bolt", quantity, unitPrice);

    assertThat(refused.message()).isEqualTo(message);
    assertThat(refused.isError()).isTrue();
  }

  @Test
  void productIsRequired() {
    assertThat(newOrder("Acme Corp", "  ", "1", "1").message()).isEqualTo("ORA-20001: Product is required");
  }

  @Test
  void editingAnOrderRecomputesItsTotal() {
    Screen edited = ui.on(order("1")).fill("Quantity", "10").press("Save");

    assertThat(edited.message()).isEqualTo(SAVED);
    assertThat(edited.field("Total")).isEqualTo("190.00");
    assertThat(edited.field("Discount %")).isEqualTo("5");
  }

  @Test
  void editingAnOrderOverTheCreditLimitKeepsTheOldValues() {
    Screen refused = ui.on(order("1")).fill("Quantity", "1000").press("Save");

    assertThat(refused.message()).startsWith("ORA-20002: Credit limit exceeded");
    assertThat(order("1").field("Quantity")).isEqualTo("5");
  }

  @Test
  void aShippedOrderCanNoLongerBeChangedCancelledOrDeleted() {
    Screen shipped = ui.on(order("1")).press("Ship");
    assertThat(shipped.field("Status")).isEqualTo("SHIPPED");

    assertThat(ui.on(shipped).press("Cancel Order").message())
        .isEqualTo("ORA-20004: Only NEW orders can be cancelled (order is SHIPPED)");
    assertThat(ui.on(order("1")).press("Ship").message())
        .isEqualTo("ORA-20004: Only NEW orders can be shipped (order is SHIPPED)");
    assertThat(ui.on(order("1")).fill("Product", "Other").press("Save").message())
        .isEqualTo("ORA-20004: Only NEW orders can be changed (order is SHIPPED)");
    assertThat(ui.on(order("1")).press("Delete").message())
        .isEqualTo("ORA-20004: Shipped orders cannot be deleted");
  }

  @Test
  void aCancelledOrderCanBeDeleted() {
    Screen afterDelete = ui.on(order("3")).press("Delete");

    assertThat(afterDelete.title()).isEqualTo("Orders");
    assertThat(afterDelete.message()).isEqualTo(SAVED);
    assertThat(afterDelete.grid()).extracting(r -> r.get("Id")).containsExactly("1", "2");
  }

  @Test
  void queryByStatusCustomerAndProduct() {
    Screen orders = ui.menu("Orders");

    assertThat(ui.on(orders).fill("Status", "SHIPPED").press("Execute Query").grid())
        .extracting(r -> r.get("Product")).containsExactly("Gadget");
    assertThat(ui.on(orders).fill("Customer", "Globex").press("Execute Query").grid())
        .extracting(r -> r.get("Product")).containsExactly("Sprocket");
    assertThat(ui.on(orders).fill("Product", "adg").press("Execute Query").grid())
        .extracting(r -> r.get("Product")).containsExactly("Gadget");
  }

  @Test
  void everyChangeLeavesATraceInTheAuditLog() {
    Screen saved = newOrder("Acme Corp", "Bolt", "2", "3");
    ui.on(saved).press("Ship");

    assertThat(ui.menu("Audit Log").grid())
        .extracting(r -> Map.of("action", r.get("Action"), "new", r.get("New Values")))
        .containsExactly(
            Map.of("action", "INSERT", "new", "customer_id=1;product=Bolt;quantity=2;unit_price=3.00;total=6.00;status=NEW"),
            Map.of("action", "UPDATE", "new", "customer_id=1;product=Bolt;quantity=2;unit_price=3.00;total=6.00;status=SHIPPED"));
  }

  @Test
  void aRefusedOrderLeavesNoTraceInTheAuditLog() {
    newOrder("Globex", "Gold", "1", "5000");

    assertThat(ui.menu("Audit Log").grid()).isEmpty();
  }
}
