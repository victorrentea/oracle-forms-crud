package ro.victorrentea.formsui.pw;

import java.util.function.Supplier;

/**
 * Something on the Forms screen, read afresh every time it is asked: assertions on it auto-wait.
 * It knows its app and where it is drawn, so the trace can outline it.
 */
public class Locator {
  private final String description;
  private final Supplier<String> text;
  final FormsApp app;
  final Supplier<Trace.Box> where;

  public Locator(String description, Supplier<String> text, FormsApp app, Supplier<Trace.Box> where) {
    this.description = description;
    this.text = text;
    this.app = app;
    this.where = where;
  }

  public String textContent() {
    return text.get();
  }

  @Override
  public String toString() {
    return description;
  }
}
