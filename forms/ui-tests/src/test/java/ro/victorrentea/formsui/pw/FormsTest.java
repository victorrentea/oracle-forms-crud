package ro.victorrentea.formsui.pw;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

/**
 * @FormsTest lends each test method a {@link FormsApp} from the {@link FormsPool} (its own worker:
 * client + schema, freshly reset), gives it back afterwards and writes the HTML trace to
 * target/forms-report/.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@org.junit.jupiter.api.extension.ExtendWith(FormsTest.Extension.class)
public @interface FormsTest {

  class Extension implements ParameterResolver, AfterEachCallback {
    private static final List<String[]> RESULTS = new ArrayList<>();

    @Override
    public boolean supportsParameter(ParameterContext p, ExtensionContext e) {
      return p.getParameter().getType() == FormsApp.class;
    }

    @Override
    public Object resolveParameter(ParameterContext p, ExtensionContext e) {
      FormsApp app = FormsPool.acquire(e.getRequiredTestClass().getSimpleName() + "." + e.getRequiredTestMethod().getName());
      e.getStore(ExtensionContext.Namespace.GLOBAL).put("app", app);
      return app;
    }

    @Override
    public void afterEach(ExtensionContext e) throws IOException {
      FormsApp app = e.getStore(ExtensionContext.Namespace.GLOBAL).remove("app", FormsApp.class);
      if (app == null) return;
      Throwable failure = e.getExecutionException().orElse(null);
      String file = app.trace().write(failure == null, failure, failure == null ? null : app.screenshot());
      FormsPool.release(app, failure == null);
      synchronized (RESULTS) {
        RESULTS.add(new String[]{e.getDisplayName(), failure == null ? "PASSED" : "FAILED", file});
        Trace.writeIndex(RESULTS);
      }
    }
  }
}
