package ro.victorrentea.formsui.pw;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Playwright-style web-first assertions for Forms: they keep re-reading the screen until it
 * matches or the timeout passes, because Forms answers asynchronously from its server.
 */
public final class Expect {
  static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("expectTimeoutSeconds", 15));
  private static ThreadLocal<Trace> currentTrace = new ThreadLocal<>();

  private final Locator locator;

  private Expect(Locator locator) {
    this.locator = locator;
  }

  public static Expect expect(Locator locator) {
    return new Expect(locator);
  }

  static void traceTo(Trace trace) {
    currentTrace.set(trace);
  }

  /** The trace of the test running on this thread, if any. */
  public static Trace currentTrace() {
    return currentTrace.get();
  }

  public void toHaveText(String expected) {
    check("toHaveText", expected, actual -> actual.equals(expected));
  }

  public void toContainText(String expected) {
    check("toContainText", expected, actual -> actual.contains(expected));
  }

  private void check(String matcher, String expected, java.util.function.Predicate<String> ok) {
    String what = matcher.equals("toHaveText") ? "to have text \"" + expected + "\"" : "to contain \"" + expected + "\"";
    Trace trace = currentTrace.get();
    Trace.Step step = trace == null ? null
        : trace.action("Expect \"" + matcher + "\"  " + locator, "expect", java.util.Map.of("locator", locator.toString(), "expected", expected));
    String[] last = {null};
    try {
      poll(locator + " " + what, TIMEOUT, () -> {
        last[0] = locator.textContent();
        return ok.test(last[0]) ? true : null;
      });
      if (step != null) trace.end(step, locator.app.tracedScreenshot(), box(), false, true, null);
    } catch (AssertionError e) {
      String message = "expected " + locator + " " + what + ", but it was \"" + last[0] + "\"";
      if (step != null) trace.end(step, locator.app.screenshot(), box(), false, false, message);
      throw new AssertionError(message, e);
    }
  }

  private Trace.Box box() {
    try {
      return locator.where == null ? null : locator.where.get();
    } catch (RuntimeException notOnScreen) {
      return null;
    }
  }

  /** Repeats the probe until it returns non-null. */
  public static <T> T poll(String what, Duration timeout, Supplier<T> probe) {
    long deadline = System.nanoTime() + timeout.toNanos();
    RuntimeException lastError = null;
    while (System.nanoTime() < deadline) {
      try {
        T value = probe.get();
        if (value != null) return value;
      } catch (RuntimeException e) {
        lastError = e;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("Timed out after " + timeout.toSeconds() + "s waiting for " + what, lastError);
  }
}
