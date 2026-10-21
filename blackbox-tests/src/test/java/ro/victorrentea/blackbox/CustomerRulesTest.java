package ro.victorrentea.blackbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.victorrentea.blackbox.OrderRulesTest.SAVED;

/** The customer rules, through the Customers screens. */
class CustomerRulesTest {
  Ui ui = new Ui();

  @BeforeEach
  void knownData() {
    Stack.resetData();
  }

  Screen newCustomer(String name, String creditLimit) {
    Screen form = ui.click(ui.menu("Customers"), "New Customer");
    return ui.on(form).fill("Name", name).fill("Credit Limit", creditLimit).press("Save");
  }

  Screen customer(String name) {
    String id = ui.menu("Customers").grid().stream()
        .filter(r -> r.get("Name").equals(name)).findFirst().orElseThrow().get("Id");
    return ui.click(ui.menu("Customers"), id);
  }

  @Test
  void createACustomer() {
    Screen saved = newCustomer("  Umbrella  ", "750");

    assertThat(saved.message()).isEqualTo(SAVED);
    assertThat(saved.field("Name")).as("trimmed").isEqualTo("Umbrella");
    assertThat(saved.field("Active")).isEqualTo("Y");
    assertThat(ui.menu("Customers").grid()).anySatisfy(r -> {
      assertThat(r.get("Name")).isEqualTo("Umbrella");
      assertThat(r.get("Credit Limit")).isEqualTo("750.00");
      assertThat(r.get("Open Exposure")).isEqualTo("0.00");
    });
  }

  @Test
  void customerNamesAreUnique() {
    assertThat(newCustomer("Globex", "1").message()).isEqualTo("ORA-20007: Customer \"Globex\" already exists");
  }

  @Test
  void nameAndAPositiveCreditLimitAreRequired() {
    assertThat(newCustomer("", "1").message()).isEqualTo("ORA-20001: Customer name is required");
    assertThat(newCustomer("X", "-5").message()).isEqualTo("ORA-20001: Credit limit must be zero or positive");
    assertThat(newCustomer("X", "").message()).isEqualTo("ORA-20001: Credit limit must be zero or positive");
  }

  @Test
  void exposureCountsNewAndShippedOrdersOnly() {
    assertThat(ui.menu("Customers").grid())
        .extracting(r -> r.get("Name") + "=" + r.get("Open Exposure"))
        .containsExactly("Acme Corp=670.00", "Globex=0.00", "Initech=0.00");
  }

  @Test
  void aCustomerWithOrdersCannotBeDeleted() {
    Screen refused = ui.on(customer("Acme Corp")).press("Delete");

    assertThat(refused.message()).isEqualTo("ORA-20005: Customer has 2 order(s) and cannot be deleted");
  }

  @Test
  void aCustomerWithoutOrdersCanBeDeleted() {
    Screen afterDelete = ui.on(customer("Initech")).press("Delete");

    assertThat(afterDelete.message()).isEqualTo(SAVED);
    assertThat(afterDelete.grid()).extracting(r -> r.get("Name")).containsExactly("Acme Corp", "Globex");
  }

  @Test
  void aDeactivatedCustomerDisappearsFromTheNewOrderList() {
    ui.on(customer("Globex")).fill("Active", "N").press("Save");

    Screen form = ui.click(ui.menu("Orders"), "New Order");
    assertThat(form.options("Customer")).containsExactly("Acme Corp");
  }
}
