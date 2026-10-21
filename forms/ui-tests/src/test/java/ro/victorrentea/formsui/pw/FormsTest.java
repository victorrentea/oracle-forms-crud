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

/**
 * @FormsTest lends each test method a {@link FormsApp} from the {@link FormsPool} (its own worker:
 * client + schema, freshly reset), gives it back afterwards and saves the test's Playwright trace
 * to target/forms-traces/ (see ../show-traces.sh).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@org.junit.jupiter.api.extension.ExtendWith(FormsTest.Extension.class)
public @interface FormsTest {

  class Extension implements ParameterResolver, AfterEachCallback {
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
      TraceIndex.save(app, failure);
      FormsPool.release(app, failure == null);
    }
  }
}
