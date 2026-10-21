package ro.victorrentea.formsui.pw;

import java.util.function.Supplier;

/** Something on the Forms screen, read afresh every time it is asked: assertions on it auto-wait. */
public class Locator {
  private final String description;
  private final Supplier<String> text;

  public Locator(String description, Supplier<String> text) {
    this.description = description;
    this.text = text;
  }

  public String textContent() {
    return text.get();
  }

  @Override
  public String toString() {
    return description;
  }
}
