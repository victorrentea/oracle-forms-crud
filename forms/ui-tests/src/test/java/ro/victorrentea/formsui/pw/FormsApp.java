package ro.victorrentea.formsui.pw;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A running Oracle Forms client, the way Playwright's Page is a running browser tab.
 * Clients are expensive to start, so {@link FormsPool} keeps one per parallel worker and each
 * test begins with {@link #startTest}: discard everything, reset the worker's data, re-query.
 * Headless by default: the client runs on a virtual display in the Lima VM. -Dheaded=true
 * opens it on this Mac instead, slowed down so you can follow it.
 */
public class FormsApp implements AutoCloseable {
  public static final boolean HEADED = Boolean.getBoolean("headed");
  /** A Playwright trace per test, with a screenshot after every key and the SQL sent meanwhile. -Dtrace=false for speed. */
  static final boolean TRACE = !"false".equals(System.getProperty("trace"));
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String DB_URL = System.getProperty("dbUrl", "jdbc:oracle:thin:@127.0.0.1:46371/FREEPDB1");

  private final int worker;
  private final String driver;
  private final String schema;
  private final Process process;
  private Trace trace;
  private java.time.Instant dbClockAtStart; // the database's clock when the trace's clock read traceClockAtStart
  private double traceClockAtStart;

  /** The raw screen the driver reports: grid cells, buttons, status line, cursor position. */
  public record Snapshot(List<List<String>> rows, List<String> buttons, String status, int cursorRow, int cursorCol) {
  }

  private FormsApp(int worker, Process process) {
    this.worker = worker;
    this.driver = "http://127.0.0.1:" + (9970 + worker);
    this.schema = "app" + worker;
    this.process = process;
  }

  /** Starts the client of one worker: config [orders&lt;n&gt;], schema APP&lt;n&gt;, driver port 9970+n. */
  static FormsApp launch(int worker) {
    endLeftoverFormsSessions("APP" + worker);
    try {
      File forms = new File("..").getCanonicalFile();
      new File("target").mkdirs();
      ProcessBuilder pb = new ProcessBuilder(new File(forms, HEADED ? "run-client.sh" : "run-client-headless.sh").getPath())
          .redirectErrorStream(true)
          .redirectOutput(new File("target/forms-client-" + worker + ".log"));
      pb.environment().put("FORMS_CONFIG", "orders" + worker);
      pb.environment().put("DRIVER_PORT", String.valueOf(9970 + worker));
      FormsApp app = new FormsApp(worker, pb.start());
      Expect.poll("worker " + worker + "'s form to open", Duration.ofSeconds(120), () -> {
        Snapshot s = app.snapshot();
        return !s.rows().isEmpty() && s.status().startsWith("Record:") ? s : null;
      });
      return app;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A clean slate for the next test, without restarting the client. */
  void startTest(Trace trace) {
    this.trace = trace;
    clearForm(); // discard pending changes and their row locks before the data is reset
    resetData();
    clearForm(); // re-query: now it shows the fresh data
    Expect.poll("the fresh orders", Duration.ofSeconds(15), () -> {
      Snapshot s = snapshot();
      return s.status().contains("Record: 1/3") && s.rows().get(3).get(0).isBlank() && s.cursorRow() == 0 ? true : null;
    });
    trace.screen(screenshot());
    if (TRACE) {
      traceClockAtStart = trace.now();
      dbClockAtStart = dbClock();
    }
  }

  /** Copies into the trace what this worker's Forms runtime sent to the database during the test. */
  public void collectSql() {
    if (!TRACE || dbClockAtStart == null) return;
    String sql = """
        select to_char(event_timestamp_utc, 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"') ts, action_name, object_name,
               dbms_lob.substr(sql_text, 4000), dbms_lob.substr(sql_binds, 4000), return_code
          from unified_audit_trail
         where dbusername = ? and unified_audit_policies like '%OFC_FORMS_TRACE%'
           and event_timestamp_utc >= to_timestamp(?, 'YYYY-MM-DD"T"HH24:MI:SS.FF3"Z"')
         order by event_timestamp_utc, entry_id""";
    try (var c = DriverManager.getConnection(DB_URL, "system", "oracle"); var ps = c.prepareStatement(sql)) {
      ps.setString(1, schema.toUpperCase());
      ps.setString(2, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
          .withZone(java.time.ZoneOffset.UTC).format(dbClockAtStart));
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          double at = traceClockAtStart + java.time.Duration.between(dbClockAtStart, java.time.Instant.parse(rs.getString(1))).toNanos() / 1e6;
          trace.sql(at, schema.toUpperCase(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read the audit trail", e);
    }
  }

  private java.time.Instant dbClock() {
    try (var c = DriverManager.getConnection(DB_URL, schema, "app"); var st = c.createStatement();
         var rs = st.executeQuery("select to_char(sys_extract_utc(systimestamp), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3\"Z\"') from dual")) {
      rs.next();
      return java.time.Instant.parse(rs.getString(1));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * F8, Clear Form (the form's KEY-CLRFRM): roll back, re-query, cursor on the first order.
   * The previous test's message may stay in front of "Record: n/m", hence contains().
   * A key sent while Forms is still busy with the previous round trip can be lost: press again.
   */
  private void clearForm() {
    for (int attempt = 1; ; attempt++) {
      pressKey("F8");
      try {
        Expect.poll("the form to clear", Duration.ofSeconds(5), () -> {
          Snapshot s = snapshot();
          return s.status().contains("Record: 1/") && s.cursorRow() == 0 ? true : null;
        });
        return;
      } catch (AssertionError notYet) {
        if (attempt == 3) throw notYet;
      }
    }
  }

  // ---- what a user sees ----

  public Snapshot snapshot() {
    List<List<String>> rows = new ArrayList<>();
    List<String> buttons = new ArrayList<>();
    String status = "";
    int row = -1, col = -1;
    for (String line : call("/screen").split("\n")) {
      if (line.startsWith("row ")) {
        rows.add(Arrays.stream(line.substring(4).split("\\|", -1)).map(String::trim).toList());
      } else if (line.startsWith("button ")) {
        buttons.add(line.substring(7));
      } else if (line.startsWith("status ")) {
        status = line.substring(7);
      } else if (line.matches("focus \\d+,\\d+")) {
        String[] rc = line.substring(6).split(",");
        row = Integer.parseInt(rc[0]);
        col = Integer.parseInt(rc[1]);
      }
    }
    return new Snapshot(rows, buttons, status, row, col);
  }

  public byte[] screenshot() {
    try {
      HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(driver + "/screenshot")).timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofByteArray());
      return r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").startsWith("image") ? r.body() : null;
    } catch (IOException | InterruptedException e) {
      return null;
    }
  }

  // ---- locators ----

  /** The message area of the status bar: messages, errors and the hint of the current field. */
  public Locator messageLine() {
    return new Locator("message line", () -> {
      String status = snapshot().status();
      int record = status.indexOf("Record:");
      return (record < 0 ? status : status.substring(0, record)).replaceAll("\\s*/\\s*$", "").trim();
    }, this, null);
  }

  /** The "Record: n/m" indicator of the status bar. */
  public Locator recordIndicator() {
    return new Locator("record indicator", () -> {
      String status = snapshot().status();
      int record = status.indexOf("Record:");
      return record < 0 ? "" : status.substring(record).trim();
    }, this, null);
  }

  public Locator cell(int row, int column, String columnName) {
    return new Locator(columnName + " of line " + (row + 1), () -> snapshot().rows().get(row).get(column),
        this, () -> where("row=" + row + "&col=" + column));
  }

  public ButtonLocator button(String caption) {
    return new ButtonLocator(this, caption);
  }

  // ---- what a user does: the keyboard (Forms is a keyboard application) ----

  /** A Forms function key: "Tab", "Down", "Control+S" (Commit), "F8" (Clear Form)... */
  public FormsApp press(String key) {
    Trace.Step step = trace.action("Press \"" + key + "\"", "pw:api", java.util.Map.of("key", key));
    Trace.Box target = TRACE ? focusBox() : null; // where the key goes
    pressKey(key);
    trace.end(step, tracedScreenshot(), target, false, true, null);
    return this;
  }

  public FormsApp type(String text) {
    Trace.Step step = trace.action("Type \"" + text + "\"", "pw:api", java.util.Map.of("text", text));
    Trace.Box target = TRACE ? focusBox() : null;
    call("/type?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
    settle();
    trace.end(step, tracedScreenshot(), target, false, true, null);
    return this;
  }

  void click(String caption) {
    Trace.Step step = trace.action("Click \"" + caption + "\"", "pw:api", java.util.Map.of("button", caption));
    Trace.Box target = TRACE ? where("button=" + URLEncoder.encode(caption, StandardCharsets.UTF_8)) : null;
    call("/click?button=" + URLEncoder.encode(caption, StandardCharsets.UTF_8));
    settle();
    trace.end(step, tracedScreenshot(), target, true, true, null);
  }

  /** Where a component is drawn on the screenshot: "row=..&col=..", "button=..", or "" for the cursor. */
  Trace.Box where(String query) {
    String[] r = call("/where?" + query).trim().split(",");
    return new Trace.Box(Integer.parseInt(r[0]), Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3]));
  }

  private Trace.Box focusBox() {
    try {
      return where("focus");
    } catch (RuntimeException noFocus) {
      return null;
    }
  }

  byte[] tracedScreenshot() {
    return TRACE ? screenshot() : null;
  }

  private void pressKey(String key) {
    call("/key?name=" + URLEncoder.encode(key.replace("Control+", "ctrl+").replace("Shift+", "shift+"), StandardCharsets.UTF_8));
    settle();
  }

  public Trace trace() {
    return trace;
  }

  @Override
  public void close() {
    try {
      call("/key?name=F4"); // Exit, so its server-side runtime ends now
      Thread.sleep(500);
    } catch (RuntimeException | InterruptedException alreadyGone) {
      // nothing to exit
    }
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
      if (!HEADED) {
        // closing the local "limactl shell" does not reach the JVM in the VM; it also ignores
        // SIGTERM (a shutdown hook never returns): SIGKILL it, matched by its own driver port
        new ProcessBuilder("limactl", "shell", "forms", "bash", "-c",
            "for p in $(pgrep -f '^/usr/lib/jvm/.*forms-driver.jar=" + (9970 + worker) + " '); do kill -9 $p; done; true")
            .inheritIO().start().waitFor();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---- fixture: this worker's own schema ----

  private void resetData() {
    Expect.poll("worker " + worker + "'s data to reset", Duration.ofSeconds(30), () -> {
      try (var c = DriverManager.getConnection(DB_URL, schema, "app"); var call = c.prepareCall("{call demo_data.reset}")) {
        call.execute();
        return true;
      } catch (SQLException lockedStill) {
        return null;
      }
    });
  }

  /** A killed client leaves its server-side runtime (and row locks) alive until timeout. */
  private static void endLeftoverFormsSessions(String user) {
    String plsql = """
        begin
          for s in (select sid, serial# from v$session where username = '%s' and program like 'frmweb%%') loop
            execute immediate 'alter system kill session ''' || s.sid || ',' || s.serial# || ''' immediate';
          end loop;
        end;""".formatted(user);
    try (var c = DriverManager.getConnection(DB_URL, "system", "oracle"); var call = c.prepareCall(plsql)) {
      call.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot end leftover Forms sessions of " + user, e);
    }
  }

  private String call(String path) {
    try {
      HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(driver + path)).timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.body().startsWith("ERROR")) throw new IllegalStateException(path + " -> " + r.body());
      return r.body();
    } catch (IOException e) {
      throw new IllegalStateException("forms-driver not reachable at " + driver, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void settle() {
    try {
      Thread.sleep(Long.getLong("keyDelayMs", HEADED ? 700 : 120));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
