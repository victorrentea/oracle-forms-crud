package ro.victorrentea.formsui.pw;

import tools.jackson.databind.json.JsonMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Records one test as a Playwright trace (format v10), so the regular Playwright Trace Viewer can
 * replay a test of the Oracle Forms desktop client:
 * <ul>
 *   <li>Actions: steps (page-object methods, Gherkin steps) with nested key presses, typing,
 *       clicks and expects, each pointing at its line in the Java test or the .feature file</li>
 *   <li>Snapshots: the Forms window before/at/after every action, the target outlined</li>
 *   <li>Network: what the Forms runtime sent to Oracle meanwhile (queries, DML, package calls)</li>
 * </ul>
 */
public class Trace {
  public record Box(int x, int y, int width, int height) {
  }

  /** One entry of the trace: an action, an expect or a group. */
  public static final class Step {
    final String id;
    final String parentId;
    final String title;
    final String method; // test.step | pw:api | expect
    final Map<String, Object> params;
    final double start;
    final StackTraceElement source;
    double end = -1;
    String error;
    byte[] before, after; // JPEG of the Forms window
    Box box;
    boolean click;
    boolean passed = true;

    Step(String id, String parentId, String title, String method, Map<String, Object> params, double start, StackTraceElement source) {
      this.id = id;
      this.parentId = parentId;
      this.title = title;
      this.method = method;
      this.params = params;
      this.start = start;
      this.source = source;
    }
  }

  private record Sql(double at, String user, String action, String object, String text, String binds, int returnCode) {
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PAGE = "page@forms";
  private static final String FRAME = "frame@forms";
  private static final String SOURCE_ROOT = "ro.victorrentea.formsui.";

  private final String testName;
  private final long startNanos = System.nanoTime();
  private final long wallStart = System.currentTimeMillis();
  private final List<Step> steps = new ArrayList<>();
  private final List<Sql> sql = new ArrayList<>();
  private final Deque<Step> openGroups = new ArrayDeque<>();
  private byte[] lastScreen;
  private int width = 1100, height = 620;
  private String testError;
  private StackTraceElement gherkinSource; // the .feature line of the running Gherkin step

  public Trace(String testName) {
    this.testName = testName;
    Expect.traceTo(this);
  }

  /** Milliseconds since the trace started: the trace's own clock. */
  public double now() {
    return (System.nanoTime() - startNanos) / 1e6;
  }

  public long wallStart() {
    return wallStart;
  }

  public String testName() {
    return testName;
  }

  // ---- grouping: page-object methods and Gherkin steps ----

  public <T> T group(String title, Supplier<T> body) {
    Step group = begin(title, "test.step", map());
    openGroups.push(group);
    try {
      return body.get();
    } catch (RuntimeException | Error e) {
      group.error = String.valueOf(e.getMessage());
      throw e;
    } finally {
      openGroups.pop();
      group.end = now();
    }
  }

  public void group(String title, Runnable body) {
    group(title, () -> {
      body.run();
      return null;
    });
  }

  /** For Cucumber: the step that runs now, as an open group, and its line in the .feature file. */
  public void openGherkinStep(String title, String featureFile, int line) {
    gherkinSource = new StackTraceElement("feature", "step", featureFile, line);
    openGroups.push(begin(title, "test.step", map()));
  }

  public void closeGherkinStep(String error) {
    if (openGroups.isEmpty()) return;
    Step step = openGroups.pop();
    step.end = now();
    step.error = error;
    gherkinSource = null;
  }

  // ---- actions ----

  /** Opens an action; finish it with {@link #end}. */
  public Step action(String title, String method, Map<String, Object> params) {
    Step step = begin(title, method, params);
    step.before = lastScreen;
    return step;
  }

  public void end(Step step, byte[] screenshotPng, Box target, boolean click, boolean passed, String error) {
    step.after = screenshotPng == null ? null : jpeg(screenshotPng);
    if (step.after != null) lastScreen = step.after;
    step.box = target;
    step.click = click;
    step.passed = passed;
    step.error = error;
    step.end = now();
  }

  /** The very first picture, before any action. */
  public void screen(byte[] screenshotPng) {
    if (screenshotPng != null) lastScreen = jpeg(screenshotPng);
  }

  public void sql(double at, String user, String action, String object, String text, String binds, int returnCode) {
    sql.add(new Sql(at, user, action, object, text, binds, returnCode));
  }

  public void fail(Throwable failure) {
    testError = failure == null ? null : String.valueOf(failure.getMessage());
  }

  private Step begin(String title, String method, Map<String, Object> params) {
    String parent = openGroups.isEmpty() ? null : openGroups.peek().id;
    Step step = new Step("forms@" + (steps.size() + 1), parent, title, method, params, now(), source());
    steps.add(step);
    return step;
  }

  /** The line of the test (or .feature) that caused this action: shown in the viewer's Source tab. */
  private StackTraceElement source() {
    if (gherkinSource != null) return gherkinSource;
    for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
      if (e.getClassName().startsWith(SOURCE_ROOT) && !e.getClassName().startsWith(SOURCE_ROOT + "pw.")) return e;
    }
    return null;
  }

  // ---- writing the zip ----

  /** Writes target/forms-traces/&lt;test&gt;.zip; open it with: npx playwright show-trace &lt;zip&gt; */
  public Path write() throws IOException {
    Path dir = Path.of("target", "forms-traces");
    Files.createDirectories(dir);
    Path zip = dir.resolve(testName.replaceAll("[^A-Za-z0-9_-]+", "_") + ".zip");
    for (Step s : steps) if (s.end < 0) s.end = now();
    if (lastScreen != null) {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(lastScreen));
      width = img.getWidth();
      height = img.getHeight();
    }
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      Map<String, String> sources = new LinkedHashMap<>();
      put(out, "test.trace", testTrace(sources));
      put(out, "0-trace.trace", contextTrace(out));
      put(out, "0-trace.network", network(out));
      for (Map.Entry<String, String> src : sources.entrySet()) {
        Path file = Path.of(src.getKey());
        if (Files.exists(file)) put(out, "src/" + sha1(src.getKey().getBytes(StandardCharsets.UTF_8)) + "." + extension(file), Files.readString(file));
      }
    }
    return zip;
  }

  private String testTrace(Map<String, String> sources) {
    List<Object[]> events = new ArrayList<>(); // {time, order, json}
    events.add(new Object[]{-1.0, 0, line(map("version", 10, "type", "context-options", "origin", "testRunner",
        "browserName", "", "playwrightVersion", "1.64.1", "options", map(), "platform", "darwin",
        "wallTime", wallStart, "monotonicTime", 0, "sdkLanguage", "javascript", "testTimeout", 0, "annotations", List.of()))});
    int order = 1;
    for (Step s : steps) {
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("type", "before");
      before.put("callId", s.id);
      if (s.parentId != null) before.put("parentId", s.parentId);
      before.put("startTime", s.start);
      before.put("class", "Test");
      before.put("method", s.method);
      before.put("title", s.title);
      before.put("params", s.params);
      before.put("stack", stack(s, sources));
      events.add(new Object[]{s.start, order++, line(before)});
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("type", "after");
      after.put("callId", s.id);
      after.put("endTime", s.end);
      after.put("annotations", List.of());
      if (s.error != null) after.put("error", map("name", "", "message", s.error));
      events.add(new Object[]{s.end, order++, line(after)});
    }
    if (testError != null) events.add(new Object[]{Double.MAX_VALUE, order, line(map("type", "error", "message", testError))});
    events.sort(Comparator.<Object[]>comparingDouble(e -> (Double) e[0]).thenComparingInt(e -> (Integer) e[1]));
    StringBuilder out = new StringBuilder();
    for (Object[] e : events) out.append(e[2]).append('\n');
    return out.toString();
  }

  private List<Map<String, Object>> stack(Step s, Map<String, String> sources) {
    if (s.source == null) return List.of();
    String file = sourceFile(s.source);
    if (file == null) return List.of();
    sources.put(file, file);
    return List.of(map("file", file, "line", s.source.getLineNumber(), "column", 1));
  }

  private static String sourceFile(StackTraceElement e) {
    if ("feature".equals(e.getClassName())) return e.getFileName();
    String cls = e.getClassName().replaceAll("\\$.*", "");
    Path p = Path.of("src/test/java", cls.replace('.', '/') + ".java").toAbsolutePath();
    return Files.exists(p) ? p.toString() : null;
  }

  private String contextTrace(ZipOutputStream out) throws IOException {
    StringBuilder t = new StringBuilder();
    t.append(line(map("version", 10, "type", "context-options", "origin", "library", "browserName", "chromium",
        "playwrightVersion", "1.64.1", "options", map("viewport", map("width", width, "height", height)),
        "platform", "darwin", "wallTime", wallStart, "monotonicTime", 0, "sdkLanguage", "javascript"))).append('\n');
    t.append(line(map("type", "event", "time", 0, "class", "BrowserContext", "method", "page", "params", map("pageId", PAGE)))).append('\n');
    int frame = 0;
    for (Step s : steps) {
      if (s.method.equals("test.step")) continue;
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("type", "before");
      before.put("callId", s.id);
      before.put("startTime", s.start);
      before.put("title", s.title);
      before.put("class", "Frame");
      before.put("method", s.method.equals("expect") ? "expect" : "forms");
      before.put("params", s.params);
      t.append(line(before)).append('\n');
      String color = !s.passed ? "#e5484d" : s.method.equals("expect") ? "#30a46c" : "#e5484d";
      if (s.before != null) t.append(snapshot(s, "before", s.start, s.before, null, color)).append('\n');
      if (s.box != null) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "input");
        input.put("callId", s.id);
        if (s.click) input.put("point", map("x", s.box.x() + s.box.width() / 2.0, "y", s.box.y() + s.box.height() / 2.0));
        input.put("box", map("x", s.box.x(), "y", s.box.y(), "width", s.box.width(), "height", s.box.height()));
        t.append(line(input)).append('\n');
        byte[] at = s.method.equals("expect") ? s.after : s.before;
        if (at != null) t.append(snapshot(s, "action", s.start, at, s.box, color)).append('\n');
      }
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("type", "after");
      after.put("callId", s.id);
      after.put("endTime", s.end);
      if (s.error != null) after.put("error", map("error", map("name", "Error", "message", s.error)));
      t.append(line(after)).append('\n');
      if (s.after != null) {
        t.append(snapshot(s, "after", s.end, s.after, s.box, color)).append('\n');
        String file = "screencast/" + PAGE + "-" + (wallStart + (long) s.end) + "-" + (frame++) + ".jpeg";
        put(out, file, s.after);
        t.append(line(map("type", "screencast-frame", "pageId", PAGE, "file", file, "width", width, "height", height,
            "timestamp", s.end, "frameSwapWallTime", wallStart + s.end))).append('\n');
      }
    }
    return t.toString();
  }

  /** A DOM snapshot made of the Forms window picture, with the action's target outlined on top. */
  private String snapshot(Step s, String phase, double time, byte[] jpeg, Box box, String color) {
    List<Object> body = new ArrayList<>(List.of("BODY", map("style", "margin:0;background:#fff")));
    body.add(List.of("IMG", map("src", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg),
        "style", "display:block;width:" + width + "px;height:" + height + "px")));
    if (box != null) {
      body.add(List.of("DIV", map("__playwright_target__", s.id, "style",
          "position:absolute;left:%dpx;top:%dpx;width:%dpx;height:%dpx;outline:3px solid %s;background:%s22;border-radius:2px"
              .formatted(box.x(), box.y(), box.width(), box.height(), color, color))));
    }
    List<Object> html = List.of("HTML", map(), List.of("HEAD", map(), List.of("TITLE", map(), "Oracle Forms")), body);
    Map<String, Object> snap = new LinkedHashMap<>();
    snap.put("callId", s.id);
    snap.put("phase", phase);
    snap.put("pageId", PAGE);
    snap.put("frameId", FRAME);
    snap.put("frameUrl", "about:blank");
    snap.put("html", html);
    snap.put("viewport", map("width", width, "height", height));
    snap.put("timestamp", time);
    snap.put("wallTime", wallStart + time);
    snap.put("collectionTime", 0);
    snap.put("resourceOverrides", List.of());
    snap.put("isMainFrame", true);
    return line(map("type", "frame-snapshot", "snapshot", snap));
  }

  /** Each SQL statement as a "request": method = the statement kind, URL = schema/object. */
  private String network(ZipOutputStream out) throws IOException {
    StringBuilder n = new StringBuilder();
    for (Sql q : sql) {
      String body = q.text() + (q.binds() == null || q.binds().isBlank() ? "" : "\n\n-- binds:" + q.binds());
      String bodyFile = "resources/" + sha1(body.getBytes(StandardCharsets.UTF_8)) + ".sql";
      put(out, bodyFile, body);
      String answer = q.returnCode() == 0 ? "OK" : "ORA-" + String.format("%05d", q.returnCode());
      String answerFile = "resources/" + sha1(answer.getBytes(StandardCharsets.UTF_8)) + ".txt";
      put(out, answerFile, answer);
      Map<String, Object> request = new LinkedHashMap<>();
      request.put("method", q.action());
      request.put("url", "http://oracle/" + q.user() + "/" + q.object() + "?" + q.action().toLowerCase());
      request.put("httpVersion", "SQL*Net");
      request.put("cookies", List.of());
      request.put("headers", List.of(map("name", "Database-User", "value", q.user())));
      request.put("queryString", List.of());
      request.put("headersSize", -1);
      request.put("bodySize", body.length());
      request.put("postData", map("mimeType", "text/x-sql", "text", "", "params", List.of(), "_file", bodyFile));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("status", q.returnCode() == 0 ? 200 : 500);
      response.put("statusText", answer);
      response.put("httpVersion", "SQL*Net");
      response.put("cookies", List.of());
      response.put("headers", List.of());
      response.put("content", map("size", answer.length(), "mimeType", "text/plain", "_file", answerFile));
      response.put("headersSize", -1);
      response.put("bodySize", -1);
      response.put("redirectURL", "");
      response.put("_transferSize", -1);
      Map<String, Object> snap = new LinkedHashMap<>();
      snap.put("pageref", PAGE);
      snap.put("startedDateTime", Instant.ofEpochMilli(wallStart + (long) q.at()).toString());
      snap.put("time", 1);
      snap.put("request", request);
      snap.put("response", response);
      snap.put("cache", map());
      snap.put("timings", map("dns", -1, "connect", -1, "ssl", -1, "send", 0, "wait", 1, "receive", -1));
      snap.put("_frameref", FRAME);
      snap.put("_monotonicTime", q.at());
      snap.put("_resourceType", "fetch");
      n.append(line(map("type", "resource-snapshot", "snapshot", snap))).append('\n');
    }
    return n.toString();
  }

  // ---- helpers ----

  /** Map.of keeps no order and stops at 10 entries; trace events read better in order. */
  private static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
    return m;
  }

  private static String line(Object event) {
    return JSON.writeValueAsString(event);
  }

  private static void put(ZipOutputStream out, String name, String text) throws IOException {
    put(out, name, text.getBytes(StandardCharsets.UTF_8));
  }

  private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
    try {
      out.putNextEntry(new ZipEntry(name));
    } catch (java.util.zip.ZipException duplicate) {
      return; // same content-addressed resource twice
    }
    out.write(bytes);
    out.closeEntry();
  }

  private static byte[] jpeg(byte[] png) {
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
      ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(0.85f);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
        writer.setOutput(ios);
        writer.write(null, new IIOImage(img, null, null), param);
      }
      writer.dispose();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha1(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String extension(Path file) {
    String name = file.getFileName().toString();
    return name.substring(name.lastIndexOf('.') + 1);
  }
}
