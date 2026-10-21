package ro.victorrentea.formsdriver;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and drives the Oracle Forms client from inside its JVM. Input is posted as AWT events on
 * the client's own event queue, so the user's real mouse and keyboard are never touched.
 */
final class Driver {
  private Driver() {
  }

  private static final String TEXT_FIELD = "oracle.forms.ui.VTextField";
  private static final String BUTTON = "oracle.forms.ui.VButton";

  /** The visible form as text: one line per grid row (cells joined by |), buttons, status line. */
  static String screen() {
    StringBuilder out = new StringBuilder();
    for (List<Component> row : grid()) {
      List<String> cells = new ArrayList<>();
      for (Component cell : row) cells.add(Agent.text(cell));
      out.append("row ").append(String.join(" | ", cells)).append('\n');
    }
    for (Component b : all(BUTTON)) out.append("button ").append(Agent.text(b)).append('\n');
    out.append("status ").append(status()).append('\n');
    Component focus;
    try {
      focus = focusOwner();
    } catch (IllegalStateException e) {
      focus = null;
    }
    out.append("focus ").append(focus == null ? "-" : position(focus)).append('\n');
    return out.toString();
  }

  static String click(String caption) {
    for (Component b : all(BUTTON)) {
      if (caption.equals(Agent.text(b))) {
        mouseClick(b);
        return "clicked " + caption;
      }
    }
    return "ERROR no button " + caption;
  }

  /** Puts the cursor in a grid cell, like clicking into it. Rows and columns count from 0. */
  static String cell(int row, int col) {
    List<List<Component>> grid = grid();
    Component c = grid.get(row).get(col);
    mouseClick(c);
    return "cell " + row + "," + col;
  }

  static String type(String text) {
    Component target = focusOwner();
    long now = System.currentTimeMillis();
    for (char ch : text.toCharArray()) {
      int code = KeyEvent.getExtendedKeyCodeForChar(ch);
      int mods = Character.isUpperCase(ch) ? InputEvent.SHIFT_DOWN_MASK : 0;
      post(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, mods, code, ch));
      post(new KeyEvent(target, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch));
      post(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, mods, code, ch));
    }
    return "typed " + text.length() + " chars";
  }

  /** A function key the way Forms knows it: TAB, ENTER, UP, DOWN, F1..F12, optionally "ctrl+" / "shift+". */
  static String key(String name) {
    Component target = focusOwner();
    int mods = 0;
    String key = name.toUpperCase();
    while (key.contains("+")) {
      String mod = key.substring(0, key.indexOf('+'));
      key = key.substring(key.indexOf('+') + 1);
      if (mod.equals("CTRL")) mods |= InputEvent.CTRL_DOWN_MASK;
      if (mod.equals("SHIFT")) mods |= InputEvent.SHIFT_DOWN_MASK;
      if (mod.equals("ALT")) mods |= InputEvent.ALT_DOWN_MASK;
    }
    int code;
    try {
      code = KeyEvent.class.getField("VK_" + key).getInt(null);
    } catch (ReflectiveOperationException e) {
      return "ERROR unknown key " + name;
    }
    long now = System.currentTimeMillis();
    // exactly what a real keyboard produces: TAB and ENTER carry their character too
    char ch = code == KeyEvent.VK_TAB ? '\t' : code == KeyEvent.VK_ENTER ? '\n' : KeyEvent.CHAR_UNDEFINED;
    post(new KeyEvent(target, KeyEvent.KEY_PRESSED, now, mods, code, ch));
    if (ch != KeyEvent.CHAR_UNDEFINED) {
      post(new KeyEvent(target, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch));
    }
    post(new KeyEvent(target, KeyEvent.KEY_RELEASED, now, mods, code, ch));
    return "key " + name;
  }

  // ---- helpers ----

  /**
   * Where Forms put the cursor. When the Forms window is not the active window on the desktop
   * (the user is in another app), Java keeps it as the window's most recent focus owner.
   */
  static Component focusOwner() {
    Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    if (c != null) return c;
    for (Frame f : Frame.getFrames()) {
      if (f.isVisible() && f.getMostRecentFocusOwner() != null && f.getClass().getName().startsWith("oracle.")) {
        return f.getMostRecentFocusOwner();
      }
    }
    throw new IllegalStateException("nothing has the keyboard focus");
  }

  private static void mouseClick(Component c) {
    Point p = new Point(c.getWidth() / 2, c.getHeight() / 2);
    long now = System.currentTimeMillis();
    post(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, 1, false, MouseEvent.BUTTON1));
    post(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, 0, p.x, p.y, 1, false, MouseEvent.BUTTON1));
    post(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, 0, p.x, p.y, 1, false, MouseEvent.BUTTON1));
  }

  private static void post(java.awt.AWTEvent event) {
    if (event instanceof KeyEvent) {
      deliverKey((KeyEvent) event);
    } else {
      Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(event);
    }
  }

  /**
   * AWT routes keys only to the desktop's focused window, so with the Forms window in the
   * background they would vanish. Hand them straight to the field instead, after the events
   * already queued (earlier clicks) have run.
   */
  private static void deliverKey(KeyEvent event) {
    EventQueue.invokeLater(() -> {
      try {
        Method process = Component.class.getDeclaredMethod("processEvent", java.awt.AWTEvent.class);
        process.setAccessible(true);
        process.invoke(event.getSource(), event);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** Text fields grouped in columns by x and rows by y: the multi-record block on the canvas. */
  static List<List<Component>> grid() {
    Map<Integer, List<Component>> byColumn = new TreeMap<>();
    for (Component c : all(TEXT_FIELD)) {
      byColumn.computeIfAbsent(c.getX(), x -> new ArrayList<>()).add(c);
    }
    List<List<Component>> columns = new ArrayList<>();
    for (List<Component> col : byColumn.values()) {
      col.sort(Comparator.comparingInt(Component::getY));
      columns.add(col);
    }
    int rows = columns.stream().mapToInt(List::size).max().orElse(0);
    List<List<Component>> grid = new ArrayList<>();
    for (int r = 0; r < rows; r++) {
      List<Component> row = new ArrayList<>();
      for (List<Component> col : columns) {
        if (r < col.size()) row.add(col.get(r));
      }
      grid.add(row);
    }
    return grid;
  }

  private static String position(Component focus) {
    List<List<Component>> grid = grid();
    for (int r = 0; r < grid.size(); r++) {
      int c = grid.get(r).indexOf(focus);
      if (c >= 0) return r + "," + c;
    }
    return focus.getClass().getSimpleName() + " " + Agent.text(focus);
  }

  private static String status() {
    List<String> texts = new ArrayList<>();
    for (Component bar : all("oracle.ewt.statusBar.StatusBar")) {
      try {
        Object[] items = (Object[]) bar.getClass().getMethod("getItems").invoke(bar);
        for (Object item : items) {
          for (String getter : new String[]{"getText", "getValue"}) {
            try {
              Method m = item.getClass().getMethod(getter);
              Object v = m.invoke(item);
              if (v != null && !v.toString().trim().isEmpty()) texts.add(v.toString().trim());
              break;
            } catch (NoSuchMethodException ignored) {
            }
          }
        }
      } catch (ReflectiveOperationException ignored) {
      }
    }
    return String.join(" / ", texts);
  }

  private static List<Component> all(String className) {
    List<Component> found = new ArrayList<>();
    for (Frame f : Frame.getFrames()) {
      if (f.isVisible()) collect(f, className, found);
    }
    return found;
  }

  private static void collect(Component c, String className, List<Component> found) {
    if (!c.isVisible()) return;
    if (c.getClass().getName().equals(className)) found.add(c);
    if (c instanceof Container) {
      for (Component child : ((Container) c).getComponents()) collect(child, className, found);
    }
  }
}
