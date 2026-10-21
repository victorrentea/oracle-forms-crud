package ro.victorrentea.blackbox;

import org.approvaltests.combinations.CombinationApprovals;
import org.junit.jupiter.api.Test;

/** Every pair of buttons pressed one after the other on each starting order, then the whole system state. */
class OrderLifecycleGoldenMasterTest {

  @Test
  void twoButtonsInARow() {
    String[] orders = {"1", "2", "3"}; // seeded as NEW, SHIPPED, CANCELLED
    String[] first = {"Ship", "Cancel Order", "Delete", "Save"};
    String[] second = {"Ship", "Cancel Order", "Delete", "Save"};

    CombinationApprovals.verifyAllCombinations(this::press, orders, first, second, Golden.options());
  }

  private String press(String orderId, String firstButton, String secondButton) {
    Stack.resetData();
    Ui ui = new Ui();
    Screen afterFirst = ui.on(ui.click(ui.menu("Orders"), orderId)).press(firstButton);
    String outcome = firstButton + ": " + afterFirst.message();
    if (afterFirst.hasButton(secondButton)) {
      outcome += " | " + secondButton + ": " + ui.on(afterFirst).press(secondButton).message();
    } else {
      outcome += " | " + secondButton + ": (no such button on " + afterFirst.title() + ")";
    }
    var row = ui.menu("Orders").grid().stream().filter(r -> r.get("Id").equals(orderId)).findFirst();
    return outcome + " ⇒ " + row.map(r -> r.get("Status")).orElse("gone")
           + ", audit rows: " + ui.menu("Audit Log").grid().size();
  }
}
