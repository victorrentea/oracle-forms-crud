package ro.victorrentea.formsui;

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
import java.util.function.Predicate;

/**
 * The real Oracle Forms client (Forms Standalone Launcher, a desktop Java window) with the
 * forms-driver agent inside it. Tests only use what a keyboard user has: keys, typing, buttons,
 * and what is on screen.
 */
public class FormsClient implements AutoCloseable {
  static final String DRIVER = "http://127.0.0.1:" + System.getProperty("driverPort", "9971");
  static final List<String> COLUMNS = List.of("Id", "Cust", "Customer", "Product", "Qty", "Unit Price", "Disc %", "Total", "Status");
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private final Process process;

  /** What the Orders screen shows right now. */
  public record Screen(List<List<String>> rows, List<String> buttons, String status, int focusRow, int focusCol) {
    /** Only the rows holding a record (the block shows 10 lines). */
    public List<List<String>> records() {
      return rows.stream().filter(r -> r.stream().anyMatch(c -> !c.isBlank())).toList();
    }

    public String cell(int row, String column) {
      return rows.get(row).get(COLUMNS.indexOf(column));
    }

    public List<String> column(String column) {
      return records().stream().map(r -> r.get(COLUMNS.indexOf(column))).toList();
    }
  }

  private FormsClient(Process process) {
    this.process = process;
  }

  /** Starts a fresh client on freshly reset data and waits until the orders are on screen. */
  public static FormsClient open() {
    resetData();
    try {
      File forms = new File("..").getCanonicalFile();
      Process p = new ProcessBuilder(new File(forms, "run-client.sh").getPath())
          .redirectErrorStream(true)
          .redirectOutput(new File("target/forms-client.log"))
          .start();
      FormsClient client = new FormsClient(p);
      client.waitFor(s -> !s.records().isEmpty() && s.status().startsWith("Record:"), Duration.ofSeconds(90));
      return client;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public Screen screen() {
    List<List<String>> rows = new ArrayList<>();
    List<String> buttons = new ArrayList<>();
    String status = "";
    int focusRow = -1, focusCol = -1;
    for (String line : call("/screen").split("\n")) {
      if (line.startsWith("row ")) {
        rows.add(Arrays.stream(line.substring(4).split("\\|", -1)).map(String::trim).toList());
      } else if (line.startsWith("button ")) {
        buttons.add(line.substring(7));
      } else if (line.startsWith("status ")) {
        status = line.substring(7);
      } else if (line.startsWith("focus ") && line.matches("focus \\d+,\\d+")) {
        String[] rc = line.substring(6).split(",");
        focusRow = Integer.parseInt(rc[0]);
        focusCol = Integer.parseInt(rc[1]);
      }
    }
    return new Screen(rows, buttons, status, focusRow, focusCol);
  }

  /** A Forms function key, e.g. "TAB", "DOWN", "ctrl+S" (Commit), "ctrl+F11" (Execute Query). */
  public FormsClient key(String key) {
    call("/key?name=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
    pause();
    return this;
  }

  public FormsClient type(String text) {
    call("/type?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
    pause();
    return this;
  }

  public FormsClient press(String button) {
    call("/click?button=" + URLEncoder.encode(button, StandardCharsets.UTF_8));
    pause();
    return this;
  }

  /** Waits until the screen satisfies the condition; Forms answers asynchronously from its server. */
  public Screen waitFor(Predicate<Screen> condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    Screen last = null;
    while (System.nanoTime() < deadline) {
      try {
        last = screen();
        if (condition.test(last)) return last;
      } catch (IllegalStateException driverNotUpYet) {
        // the client JVM is still starting
      }
      sleep(500);
    }
    throw new AssertionError("Screen never reached the expected state. Last screen: " + last);
  }

  public Screen waitForStatus(String expectedStart) {
    return waitFor(s -> s.status().startsWith(expectedStart), Duration.ofSeconds(30));
  }

  @Override
  public void close() {
    try {
      call("/key?name=F4"); // Exit, so the server-side runtime ends now rather than at timeout
      sleep(1500);
    } catch (RuntimeException alreadyGone) {
      // nothing to exit
    }
    // Exit may stop on "Do you want to save the changes?" when the last action was refused
    process.destroy();
    try {
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static void resetData() {
    String url = System.getProperty("dbUrl", "jdbc:oracle:thin:@127.0.0.1:46371/FREEPDB1");
    endLeftoverFormsSessions(url);
    SQLException last = null;
    for (int attempt = 0; attempt < 20; attempt++) {
      try (var c = DriverManager.getConnection(url, "app", "app"); var call = c.prepareCall("{call demo_data.reset}")) {
        call.execute();
        return;
      } catch (SQLException e) { // ORA-00054: a previous Forms session may still hold a row lock
        last = e;
        sleep(1500);
      }
    }
    throw new IllegalStateException("Cannot reset data", last);
  }

  /** A killed client leaves its server-side Forms runtime (and its row locks) alive until timeout. */
  private static void endLeftoverFormsSessions(String url) {
    String plsql = """
        begin
          for s in (select sid, serial# from v$session where username = 'APP' and program like 'frmweb%') loop
            execute immediate 'alter system kill session ''' || s.sid || ',' || s.serial# || ''' immediate';
          end loop;
        end;""";
    try (var c = DriverManager.getConnection(url, "system", "oracle"); var call = c.prepareCall(plsql)) {
      call.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot end leftover Forms sessions", e);
    }
  }

  private static String call(String path) {
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

  private static void pause() {
    sleep(Long.getLong("keyDelayMs", 400));
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
