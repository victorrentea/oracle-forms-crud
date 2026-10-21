package ro.victorrentea.blackbox;

import org.approvaltests.core.Options;

/** Golden-master conventions: what varies between runs is masked before comparing. */
final class Golden {
  private Golden() {
  }

  static Options options() {
    return new Options()
        .withScrubber(text -> text.replaceAll("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", "[timestamp]"));
  }

  /** Everything a user could look at once the scenario is over. */
  static String finalState(Ui ui) {
    return ui.menu("Orders").dump() + "\n" + ui.menu("Customers").dump() + "\n" + ui.menu("Audit Log").dump();
  }
}
