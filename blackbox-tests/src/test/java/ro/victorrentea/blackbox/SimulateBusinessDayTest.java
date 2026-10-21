package ro.victorrentea.blackbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Random;

/**
 * Not a test: stands in for a day of real users clicking through the screens, so that
 * ../capture.sh has production-like traffic to record. Seeded, so every run does the same.
 *   ../capture.sh start bb
 *   mvn test -Dtest=SimulateBusinessDayTest -Dsimulate=true
 *   ../capture.sh dump bb > src/test/resources/db-recordings/business-day.rec
 */
@EnabledIfSystemProperty(named = "simulate", matches = "true")
class SimulateBusinessDayTest {
  final Random random = new Random(2026_10_21);
  final Ui ui = new Ui();

  @Test
  void aBusyDay() {
    Stack.resetData();
    for (int i = 0; i < 60; i++) {
      switch (random.nextInt(10)) {
        case 0, 1, 2, 3 -> placeOrder();
        case 4, 5 -> pressOnSomeOrder(pick(List.of("Ship", "Ship", "Cancel Order", "Delete")));
        case 6 -> editSomeOrder();
        case 7 -> newCustomer();
        case 8 -> editSomeCustomer();
        default -> ui.on(ui.menu("Orders")).fill("Status", pick(List.of("NEW", "SHIPPED", ""))).press("Execute Query");
      }
    }
    System.out.println(String.join("\n", ui.journal()));
  }

  void placeOrder() {
    Screen form = ui.click(ui.menu("Orders"), "New Order");
    ui.on(form)
        .fill("Customer", pick(form.options("Customer")))
        .fill("Product", pick(List.of("Bolt", "Nut", "Widget", "Gear", " Spring ", "")))
        .fill("Quantity", pick(List.of("1", "3", "9", "10", "12", "50", "60", "0", "2.5", "x")))
        .fill("Unit Price", pick(List.of("0.99", "5", "19.99", "100", "250", "0", "0.004")))
        .press("Save");
  }

  void pressOnSomeOrder(String button) {
    List<String> ids = ui.menu("Orders").grid().stream().map(r -> r.get("Id")).toList();
    if (!ids.isEmpty()) {
      ui.on(ui.click(ui.menu("Orders"), pick(ids))).press(button);
    }
  }

  void editSomeOrder() {
    List<String> ids = ui.menu("Orders").grid().stream().map(r -> r.get("Id")).toList();
    if (!ids.isEmpty()) {
      ui.on(ui.click(ui.menu("Orders"), pick(ids)))
          .fill("Quantity", pick(List.of("2", "11", "49", "51", "-1")))
          .press("Save");
    }
  }

  void newCustomer() {
    Screen form = ui.click(ui.menu("Customers"), "New Customer");
    ui.on(form)
        .fill("Name", pick(List.of("Umbrella", "Hooli", "Globex", "Stark ", "")))
        .fill("Credit Limit", pick(List.of("500", "2500", "-1", "0")))
        .press("Save");
  }

  void editSomeCustomer() {
    List<String> ids = ui.menu("Customers").grid().stream().map(r -> r.get("Id")).toList();
    Screen customer = ui.click(ui.menu("Customers"), pick(ids));
    if (random.nextBoolean()) {
      ui.on(customer).fill("Active", pick(List.of("Y", "N"))).fill("Credit Limit", pick(List.of("800", "3000"))).press("Save");
    } else {
      ui.on(customer).press("Delete");
    }
  }

  <T> T pick(List<T> choices) {
    return choices.get(random.nextInt(choices.size()));
  }
}
