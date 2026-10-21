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

  public void toHaveText(String expected) {
    check("to have text \"" + expected + "\"", actual -> actual.equals(expected));
  }

  public void toContainText(String expected) {
    check("to contain \"" + expected + "\"", actual -> actual.contains(expected));
  }

  private void check(String what, java.util.function.Predicate<String> ok) {
    String[] last = {null};
    try {
      poll(locator + " " + what, TIMEOUT, () -> {
        last[0] = locator.textContent();
        return ok.test(last[0]) ? true : null;
      });
      if (currentTrace.get() != null) currentTrace.get().assertion("✓ expect " + locator + " " + what);
    } catch (AssertionError e) {
      throw new AssertionError("expected " + locator + " " + what + ", but it was \"" + last[0] + "\"", e);
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
