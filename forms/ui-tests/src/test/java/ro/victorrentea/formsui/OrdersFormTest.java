package ro.victorrentea.formsui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Orders screen of the real Oracle Forms application, used the way a clerk does: from the
 * keyboard, reading the grid and the status line. Nothing here knows about PL/SQL.
 */
class OrdersFormTest {
  static final String SAVED = "FRM-40400: Transaction complete: 1 records applied and saved.";
  FormsClient forms;

  @BeforeEach
  void openOrdersScreen() {
    forms = FormsClient.open();
  }

  @AfterEach
  void exit() {
    forms.close();
  }

  /** Down past the last order lands on a new empty line; fill it and commit with Ctrl+S. */
  FormsClient.Screen enterNewOrder(String customerId, String product, String quantity, String unitPrice) {
    int existing = forms.screen().records().size();
    for (int i = 0; i < existing; i++) {
      forms.key("DOWN");
    }
    forms.type(customerId).key("TAB").type(product).key("TAB").type(quantity).key("TAB").type(unitPrice);
    forms.key("ctrl+S");
    return forms.waitFor(s -> !s.status().startsWith("Record:") || s.records().size() > existing, Duration.ofSeconds(30));
  }

  @Test
  void theScreenOpensWithTheOrdersAlreadyQueried() {
    FormsClient.Screen screen = forms.screen();

    assertThat(screen.records()).containsExactly(
        List.of("1", "1", "Acme Corp", "Widget", "5", "20", "0", "100", "NEW"),
        List.of("2", "1", "Acme Corp", "Gadget", "12", "50", "5", "570", "SHIPPED"),
        List.of("3", "2", "Globex", "Sprocket", "3", "100", "0", "300", "CANCELLED"));
    assertThat(screen.buttons()).containsExactly("Ship", "Cancel Order");
  }

  @Test
  void aNewOrderIsSavedWithItsTotalComputed() {
    enterNewOrder("1", "Bolt", "12", "10");

    FormsClient.Screen saved = forms.waitForStatus(SAVED);
    assertThat(saved.records()).hasSize(4);
    assertThat(saved.records().get(3)).containsExactly("100", "1", "Acme Corp", "Bolt", "12", "10", "5", "114", "NEW");
  }

  @Test
  void anOrderOverTheCreditLimitIsRefused() {
    FormsClient.Screen refused = enterNewOrder("2", "Gold", "1", "5000");

    assertThat(refused.status()).contains("ORA-20002: Credit limit exceeded");
  }

  @Test
  void shippingAnOrderFromItsButton() {
    forms.press("Ship"); // the cursor starts on order 1

    FormsClient.Screen shipped = forms.waitFor(s -> "SHIPPED".equals(s.cell(0, "Status")), Duration.ofSeconds(30));
    assertThat(shipped.status()).startsWith(SAVED);
  }

  @Test
  void aShippedOrderCannotBeCancelled() {
    forms.key("DOWN"); // order 2 is SHIPPED
    forms.press("Cancel Order");

    FormsClient.Screen refused = forms.waitFor(s -> s.status().contains("ORA-20004"), Duration.ofSeconds(30));
    assertThat(refused.status()).contains("Only NEW orders can be cancelled (order is SHIPPED)");
    assertThat(refused.column("Status")).containsExactly("NEW", "SHIPPED", "CANCELLED");
  }
}
