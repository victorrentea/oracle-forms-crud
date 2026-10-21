package ro.victorrentea.formsui.bdd;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import ro.victorrentea.formsui.pw.Expect;
import ro.victorrentea.formsui.pw.Trace;

import java.nio.file.Path;

/**
 * Turns every Gherkin step into a step of the scenario's Playwright trace, with the keys and
 * expects it caused nested under it and its line of the .feature file in the Source tab.
 * Concurrent listener: the events arrive on the thread running the scenario.
 */
public class GherkinStepsInTrace implements ConcurrentEventListener {
  @Override
  public void setEventPublisher(EventPublisher publisher) {
    publisher.registerHandlerFor(TestStepStarted.class, e -> {
      Trace trace = Expect.currentTrace();
      if (trace != null && e.getTestStep() instanceof PickleStepTestStep step) {
        String feature = Path.of("src/test/resources", step.getUri().getSchemeSpecificPart().replaceFirst("^/", "")).toAbsolutePath().toString();
        trace.openGherkinStep(step.getStep().getKeyword() + step.getStep().getText(), feature, step.getStepLine());
      }
    });
    publisher.registerHandlerFor(TestStepFinished.class, e -> {
      Trace trace = Expect.currentTrace();
      if (trace != null && e.getTestStep() instanceof PickleStepTestStep) {
        Throwable error = e.getResult().getError();
        trace.closeGherkinStep(e.getResult().getStatus() == Status.PASSED ? null : String.valueOf(error == null ? e.getResult().getStatus() : error.getMessage()));
      }
    });
  }
}
