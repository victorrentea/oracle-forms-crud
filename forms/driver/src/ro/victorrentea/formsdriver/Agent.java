package ro.victorrentea.formsdriver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A Java agent loaded into the Oracle Forms client JVM (java -javaagent:forms-driver.jar -jar frmsal.jar ...).
 * It serves the client's live component tree over HTTP on 127.0.0.1:9971, so a test can see the screen.
 */
public class Agent {
  public static void premain(String args, Instrumentation inst) throws IOException {
    int port = args == null || args.isEmpty() ? 9971 : Integer.parseInt(args);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/tree", Agent::tree);
    server.createContext("/screen", e -> reply(e, onEdt(Driver::screen)));
    server.createContext("/screenshot", Agent::screenshot);
    server.createContext("/where", e -> reply(e, onEdt(() -> Driver.where(param(e, "row"), param(e, "col"), param(e, "button")))));
    server.createContext("/click", e -> reply(e, onEdt(() -> Driver.click(param(e, "button")))));
    server.createContext("/cell", e -> reply(e, onEdt(() -> Driver.cell(Integer.parseInt(param(e, "row")), Integer.parseInt(param(e, "col"))))));
    server.createContext("/type", e -> reply(e, onEdt(() -> Driver.type(param(e, "text")))));
    server.createContext("/key", e -> reply(e, onEdt(() -> Driver.key(param(e, "name")))));
    server.setExecutor(null);
    server.start();
    System.out.println("[forms-driver] listening on 127.0.0.1:" + port);
  }

  private static void tree(HttpExchange exchange) throws IOException {
    StringBuilder out = new StringBuilder();
    for (Frame frame : Frame.getFrames()) {
      dump(frame, 0, out);
    }
    for (Window window : Window.getWindows()) {
      if (!(window instanceof Frame)) {
        dump(window, 0, out);
      }
    }
    reply(exchange, out.toString());
  }

  static void dump(Component c, int depth, StringBuilder out) {
    for (int i = 0; i < depth; i++) out.append("  ");
    out.append(c.getClass().getName())
        .append(" name=").append(c.getName())
        .append(" vis=").append(c.isVisible())
        .append(" bounds=").append(c.getX()).append(',').append(c.getY()).append(',').append(c.getWidth()).append('x').append(c.getHeight());
    String text = text(c);
    if (text != null) out.append(" text=\"").append(text).append('"');
    out.append('\n');
    if (c instanceof Container) {
      for (Component child : ((Container) c).getComponents()) {
        dump(child, depth + 1, out);
      }
    }
  }

  /** A PNG of the Forms window, painted off-screen: works headless (Xvfb) and with the window hidden. */
  private static void screenshot(HttpExchange exchange) throws IOException {
    byte[][] png = new byte[1][];
    String error = onEdt(() -> {
      Frame f = Driver.formsWindow();
      if (f != null) {
        {
          java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(f.getWidth(), f.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
          java.awt.Graphics2D g = img.createGraphics();
          f.printAll(g);
          g.dispose();
          java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
          javax.imageio.ImageIO.write(img, "png", out);
          png[0] = out.toByteArray();
          return null;
        }
      }
      return "ERROR no Forms window";
    });
    if (png[0] == null) {
      reply(exchange, error);
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "image/png");
    exchange.sendResponseHeaders(200, png[0].length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(png[0]);
    }
  }

  interface Call {
    String run() throws Exception;
  }

  /** Every read and every input happens on the AWT event thread, like a real user's. */
  static String onEdt(Call call) {
    String[] result = new String[1];
    try {
      java.awt.EventQueue.invokeAndWait(() -> {
        try {
          result[0] = call.run();
        } catch (Exception ex) {
          result[0] = "ERROR " + ex;
        }
      });
    } catch (Exception ex) {
      return "ERROR " + ex;
    }
    return result[0];
  }

  static String param(HttpExchange e, String name) {
    String query = e.getRequestURI().getRawQuery();
    if (query == null) return null;
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv[0].equals(name)) {
        try {
          return java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
          throw new IllegalStateException(ex);
        }
      }
    }
    return null;
  }

  static String text(Component c) {
    for (String getter : new String[]{"getText", "getLabel", "getTitle"}) {
      try {
        Method m = c.getClass().getMethod(getter);
        Object v = m.invoke(c);
        if (v != null) return v.toString();
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  static void reply(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
