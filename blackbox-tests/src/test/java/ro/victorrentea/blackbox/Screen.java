package ro.victorrentea.blackbox;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the user sees after a request: a page read only through its visible text and labels. */
public record Screen(String path, Document page) {

  public String title() {
    return page.selectFirst("h1").text();
  }

  /** The status bar line, like the message line at the bottom of a Forms window. */
  public String message() {
    Element message = page.getElementById("message");
    return message == null ? "" : message.text();
  }

  public boolean isError() {
    Element message = page.getElementById("message");
    return message != null && message.hasClass("error");
  }

  /** The value shown in the field with this label. */
  public String field(String label) {
    Element input = input(label);
    if (input.tagName().equals("select")) {
      Element selected = input.selectFirst("option[selected]");
      return selected == null ? "" : selected.text();
    }
    if ("checkbox".equals(input.attr("type"))) {
      return input.hasAttr("checked") ? "Y" : "N";
    }
    return input.val();
  }

  /** The visible choices of a list (LOV) field. */
  public List<String> options(String label) {
    return input(label).select("option").eachText().stream().filter(t -> !t.isBlank()).toList();
  }

  /** The value submitted when the user picks this visible choice in a list field. */
  String optionValue(String label, String visibleText) {
    return input(label).select("option").stream()
        .filter(o -> o.text().equals(visibleText))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No choice '" + visibleText + "' in " + label + "; has " + options(label)))
        .val();
  }

  Element input(String label) {
    Element labelElement = page.select("label").stream()
        .filter(l -> l.text().equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No field labelled '" + label + "' on " + path));
    return page.getElementById(labelElement.attr("for"));
  }

  /** The rows of the (only) result grid on screen, each as header → cell text. */
  public List<Map<String, String>> grid() {
    Element table = page.selectFirst("table");
    if (table == null) {
      return List.of();
    }
    List<String> headers = table.select("thead th").eachText();
    List<Map<String, String>> rows = new ArrayList<>();
    for (Element tr : table.select("tbody tr")) {
      List<String> cells = tr.select("td").stream().map(Element::text).toList(); // eachText() drops empty cells
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < headers.size(); i++) {
        row.put(headers.get(i), cells.get(i));
      }
      rows.add(row);
    }
    return rows;
  }

  public boolean hasButton(String text) {
    return !page.select("button").stream().filter(b -> b.text().equals(text)).toList().isEmpty();
  }

  /** A plain-text rendering of the screen, for golden-master files. */
  public String dump() {
    StringBuilder out = new StringBuilder("== ").append(title()).append(" ==\n");
    for (Element label : page.select("main label")) {
      out.append(label.text()).append(": ").append(field(label.text())).append('\n');
    }
    List<Map<String, String>> grid = grid();
    if (page.selectFirst("table") != null) {
      out.append(String.join(" | ", page.select("thead th").eachText())).append('\n');
      grid.forEach(row -> out.append(String.join(" | ", row.values())).append('\n'));
    }
    out.append("[status] ").append(isError() ? "ERROR " : "").append(message()).append('\n');
    return out.toString();
  }
}
