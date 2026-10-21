package ro.victorrentea.blackbox;

import org.approvaltests.combinations.CombinationApprovals;
import org.junit.jupiter.api.Test;

/**
 * Characterization by brute force: try every combination of inputs on the New Order screen,
 * each from the same starting data, and pin down whatever the system answers today —
 * right or wrong. A failure means "behaviour changed", not "behaviour is wrong".
 */
class CreateOrderGoldenMasterTest {

  @Test
  void newOrderScreen() {
    String[] customers = {"Acme Corp", "Globex", ""};
    String[] quantities = {"1", "9", "10", "49", "50", "0", "-3", "1.5", "", "abc"};
    String[] unitPrices = {"19.99", "100", "0.01", "0", "x"};

    CombinationApprovals.verifyAllCombinations(this::saveNewOrder, customers, quantities, unitPrices, Golden.options());
  }

  private String saveNewOrder(String customer, String quantity, String unitPrice) {
    Stack.resetData();
    Ui ui = new Ui();
    Screen form = ui.click(ui.menu("Orders"), "New Order");
    Screen result = ui.on(form)
        .fill("Customer", customer)
        .fill("Product", "Bolt")
        .fill("Quantity", quantity)
        .fill("Unit Price", unitPrice)
        .press("Save");
    if (result.isError()) {
      return result.message();
    }
    return result.message() + " → order " + result.title().replace("Order ", "#")
           + " discount " + result.field("Discount %") + "% total " + result.field("Total");
  }
}
