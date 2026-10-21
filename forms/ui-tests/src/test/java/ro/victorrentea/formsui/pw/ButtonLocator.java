package ro.victorrentea.formsui.pw;

import java.time.Duration;

public class ButtonLocator extends Locator {
  private final FormsApp app;
  private final String caption;

  ButtonLocator(FormsApp app, String caption) {
    super("button \"" + caption + "\"", () -> app.snapshot().buttons().contains(caption) ? caption : "");
    this.app = app;
    this.caption = caption;
  }

  /** Waits for the button to be on screen, then clicks it. */
  public void click() {
    Expect.poll(this + " to be visible", Duration.ofSeconds(10), () -> app.snapshot().buttons().contains(caption) ? true : null);
    app.click(caption);
  }
}
