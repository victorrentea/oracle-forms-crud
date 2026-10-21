package ro.victorrentea.formsui.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.AfterStep;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import ro.victorrentea.formsui.OrdersPage;
import ro.victorrentea.formsui.pw.FormsApp;
import ro.victorrentea.formsui.pw.FormsPool;

import java.util.Map;

import static ro.victorrentea.formsui.pw.Expect.expect;

/** The Gherkin sentences of orders.feature, each a thin call into the OrdersPage page object. */
public class OrdersSteps {
  private FormsApp app;
  private OrdersPage orders;

  private Scenario scenario;

  /** Borrowed before the first step, so every Gherkin step of the scenario lands in its trace. */
  @io.cucumber.java.Before
  public void borrowAFormsClient(Scenario scenario) {
    this.scenario = scenario;
    app = FormsPool.acquire(scenario.getName() + " (line " + scenario.getLine() + ")"); // Outline examples share a name
    orders = new OrdersPage(app);
  }

  @Given("the Orders form is open")
  public void theOrdersFormIsOpen() {
    expect(orders.recordIndicator()).toContainText("Record: 1/");
  }

  @When("I enter a new order for customer {int}: {int} x {string} at {int}")
  public void iEnterANewOrder(int customer, int quantity, String product, int unitPrice) {
    orders.enterNewOrder(customer, product, String.valueOf(quantity), String.valueOf(unitPrice));
  }

  @When("I save")
  public void iSave() {
    orders.save();
  }

  @When("I ship the order on line {int}")
  public void iShipTheOrderOnLine(int line) {
    orders.goToLine(line).ship();
  }

  @When("I cancel the order on line {int}")
  public void iCancelTheOrderOnLine(int line) {
    orders.goToLine(line).cancelOrder();
  }

  @Then("the message line says {string}")
  public void theMessageLineSays(String message) {
    expect(orders.message()).toContainText(message);
  }

  @Then("the message line contains {string}")
  public void theMessageLineContains(String text) {
    expect(orders.message()).toContainText(text);
  }

  @Then("order line {int} shows a discount of {int}% and a total of {int}")
  public void orderLineShowsDiscountAndTotal(int line, int discount, int total) {
    expect(orders.line(line).discount()).toHaveText(String.valueOf(discount));
    expect(orders.line(line).total()).toHaveText(String.valueOf(total));
  }

  @Then("order line {int} has status {string}")
  public void orderLineHasStatus(int line, String status) {
    expect(orders.line(line).status()).toHaveText(status);
  }

  @Then("I see these orders:")
  public void iSeeTheseOrders(DataTable expected) {
    int line = 1;
    for (Map<String, String> row : expected.asMaps()) {
      for (Map.Entry<String, String> cell : row.entrySet()) {
        expect(orders.line(line).column(cell.getKey())).toHaveText(cell.getValue());
      }
      line++;
    }
  }

  /** Every step of the Cucumber HTML report gets a picture of the Forms window as it was. */
  @AfterStep
  public void screenshot(Scenario scenario) {
    if (app == null) return;
    byte[] png = app.screenshot();
    if (png != null) scenario.attach(png, "image/png", "Forms window");
  }

  @After
  public void giveTheFormBack(Scenario scenario) throws java.io.IOException {
    if (app == null) return;
    ro.victorrentea.formsui.pw.TraceIndex.save(app, scenario.isFailed() ? new AssertionError("Scenario failed: " + scenario.getName()) : null);
    FormsPool.release(app, !scenario.isFailed());
  }
}
