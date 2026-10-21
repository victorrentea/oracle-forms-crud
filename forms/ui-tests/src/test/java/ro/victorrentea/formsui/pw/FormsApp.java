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
 * Headless by default: the client runs on a virtual display in the Lima VM. -Dheaded=true
 * opens it on this Mac instead, slowed down so you can follow it.
 */
public class FormsApp implements AutoCloseable {
  static final String DRIVER = "http://127.0.0.1:" + System.getProperty("driverPort", "9971");
  public static final boolean HEADED = Boolean.getBoolean("headed");
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private final Process process;
  private final Trace trace;

  /** The raw screen the driver reports: grid cells, buttons, status line, cursor position. */
  public record Snapshot(List<List<String>> rows, List<String> buttons, String status, int cursorRow, int cursorCol) {
  }

  private FormsApp(Process process, Trace trace) {
    this.process = process;
    this.trace = trace;
  }

  /** Starts a fresh client on freshly reset data and waits until it shows the queried records. */
  public static FormsApp launch(Trace trace) {
    resetData();
    try {
      File forms = new File("..").getCanonicalFile();
      String script = HEADED ? "run-client.sh" : "run-client-headless.sh";
      new File("target").mkdirs();
      Process p = new ProcessBuilder(new File(forms, script).getPath())
          .redirectErrorStream(true)
          .redirectOutput(new File("target/forms-client.log"))
          .start();
      FormsApp app = new FormsApp(p, trace);
      Expect.poll("the form to open", Duration.ofSeconds(90), () -> {
        Snapshot s = app.snapshot();
        return !s.rows().isEmpty() && s.status().startsWith("Record:") ? s : null;
      });
      trace.step("Open the Orders form (" + (HEADED ? "on screen" : "headless") + ")", app.screenshot());
      return app;
    } catch (IOException e) {
      throw new IllegalStateException(e);
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
      HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(DRIVER + "/screenshot")).timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofByteArray());
      return r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").startsWith("image") ? r.body() : null;
    } catch (IOException | InterruptedException e) {
      return null;
    }
  }

  // ---- locators ----

  public Locator statusLine() {
    return new Locator("status line", () -> snapshot().status());
  }

  public Locator cell(int row, int column, String columnName) {
    return new Locator(columnName + " of line " + (row + 1), () -> snapshot().rows().get(row).get(column));
  }

  public ButtonLocator button(String caption) {
    return new ButtonLocator(this, caption);
  }

  // ---- what a user does: the keyboard (Forms is a keyboard application) ----

  /** A Forms function key: "Tab", "Down", "Control+S" (Commit), "F4" (Exit)... */
  public FormsApp press(String key) {
    call("/key?name=" + URLEncoder.encode(key.replace("Control+", "ctrl+").replace("Shift+", "shift+"), StandardCharsets.UTF_8));
    settle();
    trace.step("Press " + key, screenshot());
    return this;
  }

  public FormsApp type(String text) {
    call("/type?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
    settle();
    trace.step("Type \"" + text + "\"", screenshot());
    return this;
  }

  void click(String caption) {
    call("/click?button=" + URLEncoder.encode(caption, StandardCharsets.UTF_8));
    settle();
    trace.step("Click \"" + caption + "\"", screenshot());
  }

  public Trace trace() {
    return trace;
  }

  @Override
  public void close() {
    try {
      call("/key?name=F4"); // Exit: the server-side runtime ends now rather than at its timeout
      Thread.sleep(1000);
    } catch (RuntimeException | InterruptedException alreadyGone) {
      // nothing to exit
    }
    process.destroy();
    try {
      if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!HEADED) {
      // closing the local "limactl shell" does not reach the JVM inside the VM: stop it by PID
      try {
        new ProcessBuilder("limactl", "shell", "forms", "bash", "-c",
            // the Forms client JVM ignores SIGTERM (a shutdown hook never returns), so SIGKILL it;
            // the anchored pattern keeps pgrep from matching this very shell
            "for p in $(pgrep -f '^/usr/lib/jvm/.*frmsal.jar'); do kill -9 $p; done; true").inheritIO().start().waitFor();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    // the driver port is free again once the client JVM is really gone
    Expect.poll("the client to exit", Duration.ofSeconds(20), () -> {
      try {
        call("/screen");
        return null;
      } catch (IllegalStateException gone) {
        return true;
      }
    });
  }

  // ---- fixture ----

  static void resetData() {
    String url = System.getProperty("dbUrl", "jdbc:oracle:thin:@127.0.0.1:46371/FREEPDB1");
    String endLeftoverSessions = """
        begin
          for s in (select sid, serial# from v$session where username = 'APP' and program like 'frmweb%') loop
            execute immediate 'alter system kill session ''' || s.sid || ',' || s.serial# || ''' immediate';
          end loop;
        end;""";
    try (var c = DriverManager.getConnection(url, "system", "oracle"); var call = c.prepareCall(endLeftoverSessions)) {
      call.execute(); // a killed client leaves its runtime (and row locks) alive until timeout
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot end leftover Forms sessions", e);
    }
    Expect.poll("the demo data to reset", Duration.ofSeconds(30), () -> {
      try (var c = DriverManager.getConnection(url, "app", "app"); var call = c.prepareCall("{call demo_data.reset}")) {
        call.execute();
        return true;
      } catch (SQLException lockedStill) {
        return null;
      }
    });
  }

  static String call(String path) {
    try {
      HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(DRIVER + path)).timeout(Duration.ofSeconds(10)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.body().startsWith("ERROR")) throw new IllegalStateException(path + " -> " + r.body());
      return r.body();
    } catch (IOException e) {
      throw new IllegalStateException("forms-driver not reachable at " + DRIVER, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void settle() {
    try {
      Thread.sleep(Long.getLong("keyDelayMs", HEADED ? 700 : 300));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
