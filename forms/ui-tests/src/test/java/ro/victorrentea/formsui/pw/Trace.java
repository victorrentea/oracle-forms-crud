package ro.victorrentea.formsui.pw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Every action and assertion of one test, with a picture of the Forms window after each action. */
public class Trace {
  public record Step(String title, byte[] png, boolean assertion, long atMillis) {
  }

  private final String testName;
  private final long start = System.currentTimeMillis();
  private final List<Step> steps = new ArrayList<>();

  public Trace(String testName) {
    this.testName = testName;
    Expect.traceTo(this);
  }

  public void step(String title, byte[] png) {
    steps.add(new Step(title, png, false, System.currentTimeMillis() - start));
  }

  public void assertion(String title) {
    steps.add(new Step(title, null, true, System.currentTimeMillis() - start));
  }

  /** Writes target/forms-report/&lt;test&gt;.html and returns its file name. */
  public String write(boolean passed, Throwable failure, byte[] finalScreen) throws IOException {
    Path dir = Path.of("target", "forms-report");
    Files.createDirectories(dir);
    String file = testName.replaceAll("[^A-Za-z0-9_-]", "_") + ".html";
    StringBuilder html = new StringBuilder("""
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>%s</title><style>%s</style></head><body>
        <h1>%s <span class="%s">%s</span></h1><p class="meta">%d ms · <a href="index.html">all tests</a></p>
        """.formatted(testName, CSS, testName, passed ? "pass" : "fail", passed ? "PASSED" : "FAILED", System.currentTimeMillis() - start));
    if (failure != null) {
      html.append("<pre class=\"failure\">").append(escape(String.valueOf(failure.getMessage()))).append("</pre>");
    }
    html.append("<ol class=\"steps\">");
    for (Step s : steps) {
      html.append("<li class=\"").append(s.assertion() ? "assert" : "action").append("\"><div class=\"title\"><span class=\"t\">")
          .append(s.atMillis()).append(" ms</span> ").append(escape(s.title())).append("</div>");
      if (s.png() != null) html.append(img(s.png()));
      html.append("</li>");
    }
    if (failure != null && finalScreen != null) {
      html.append("<li class=\"action\"><div class=\"title\">Screen at failure</div>").append(img(finalScreen)).append("</li>");
    }
    html.append("</ol></body></html>");
    Files.writeString(dir.resolve(file), html);
    return file;
  }

  static void writeIndex(List<String[]> results) throws IOException {
    StringBuilder html = new StringBuilder("""
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        <title>Forms UI tests</title><style>%s</style></head><body><h1>Oracle Forms UI tests</h1><ul class="index">
        """.formatted(CSS));
    for (String[] r : results) {
      html.append("<li><span class=\"").append(r[1].equals("PASSED") ? "pass" : "fail").append("\">").append(r[1])
          .append("</span> <a href=\"").append(r[2]).append("\">").append(escape(r[0])).append("</a></li>");
    }
    Files.writeString(Path.of("target", "forms-report", "index.html"), html.append("</ul></body></html>"));
  }

  private static String img(byte[] png) {
    return "<img loading=\"lazy\" alt=\"Forms window\" src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png) + "\">";
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static final String CSS = """
      :root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--card:#f5f5f7;--pass:#1a7f37;--fail:#cf222e}
      @media (prefers-color-scheme:dark){:root{--bg:#161618;--fg:#f2f2f2;--muted:#a1a1a6;--card:#232326;--pass:#3fb950;--fail:#f85149}}
      body{background:var(--bg);color:var(--fg);font:15px -apple-system,system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 16px}
      h1{font-size:20px}.meta{color:var(--muted)}.pass{color:var(--pass);font-weight:600}.fail{color:var(--fail);font-weight:600}
      .steps{padding-left:24px}.steps li{margin:12px 0;padding:10px 12px;background:var(--card);border-radius:8px}
      .steps li.assert{color:var(--pass)}.t{color:var(--muted);font-variant-numeric:tabular-nums;margin-right:8px}
      img{display:block;max-width:100%;margin-top:8px;border-radius:6px;border:1px solid #8884}
      pre.failure{white-space:pre-wrap;color:var(--fail);background:var(--card);padding:12px;border-radius:8px}
      .index li{margin:6px 0}a{color:inherit}
      """;
}
