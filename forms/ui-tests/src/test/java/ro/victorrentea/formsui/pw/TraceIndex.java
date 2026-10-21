package ro.victorrentea.formsui.pw;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Saves a finished test's trace and keeps target/forms-traces/index.html listing them all. */
public final class TraceIndex {
  private static final Map<String, String[]> RESULTS = new TreeMap<>();

  private TraceIndex() {
  }

  public static void save(FormsApp app, Throwable failure) throws IOException {
    Trace trace = app.trace();
    if (!FormsApp.TRACE) return;
    app.collectSql();
    trace.fail(failure);
    Path zip = trace.write();
    synchronized (RESULTS) {
      RESULTS.put(trace.testName(), new String[]{failure == null ? "passed" : "failed", zip.getFileName().toString()});
      StringBuilder html = new StringBuilder("""
          <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
          <title>Forms traces</title><style>
          :root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--pass:#1a7f37;--fail:#cf222e;--card:#f5f5f7}
          @media (prefers-color-scheme:dark){:root{--bg:#161618;--fg:#f2f2f2;--muted:#a1a1a6;--pass:#3fb950;--fail:#f85149;--card:#232326}}
          body{background:var(--bg);color:var(--fg);font:15px -apple-system,system-ui,sans-serif;max-width:860px;margin:32px auto;padding:0 16px}
          h1{font-size:20px}p{color:var(--muted)}ul{list-style:none;padding:0}li{background:var(--card);margin:6px 0;padding:10px 14px;border-radius:8px}
          a{color:inherit;text-decoration:none}a:hover{text-decoration:underline}.passed{color:var(--pass)}.failed{color:var(--fail)}b{display:inline-block;width:64px}
          </style></head><body><h1>Oracle Forms UI tests: traces</h1>
          <p>Each test opens in the Playwright Trace Viewer: the steps on the left, the Forms window at every step,
          and under <b style="width:auto">Network</b> the SQL and package calls the Forms runtime sent to Oracle.</p><ul>
          """);
      RESULTS.forEach((name, r) -> html.append("<li><b class=\"").append(r[0]).append("\">").append(r[0])
          .append("</b> <a href=\"traceviewer/index.html?trace=../").append(r[1]).append("\">").append(name).append("</a></li>\n"));
      Files.writeString(Path.of("target", "forms-traces", "index.html"), html.append("</ul></body></html>"));
    }
  }
}
