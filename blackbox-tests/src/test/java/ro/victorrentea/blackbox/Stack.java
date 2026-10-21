package ro.victorrentea.blackbox;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the system under test runs. Taken from env BASE_URL / DB_URL, or else asked from
 * ../stack.sh for stack $STACK (default "bb"), which must already be up.
 */
public final class Stack {
  public static final String BASE_URL;
  private static final String DB_URL;

  static {
    Map<String, String> env = new HashMap<>(System.getenv());
    if (env.get("BASE_URL") == null || env.get("DB_URL") == null) {
      env.putAll(askStackScript(env.getOrDefault("STACK", "bb")));
    }
    BASE_URL = env.get("BASE_URL");
    DB_URL = env.get("DB_URL");
  }

  private Stack() {
  }

  /** A connection as the application's own database user. */
  public static Connection connect() throws SQLException {
    return DriverManager.getConnection(DB_URL, "app", "app");
  }

  /** Test fixture, not part of the behaviour under test: back to the known starting data. */
  public static void resetData() {
    try (var connection = connect();
         var call = connection.prepareCall("{call demo_data.reset}")) {
      call.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot reset data at " + DB_URL, e);
    }
  }

  private static Map<String, String> askStackScript(String stack) {
    try {
      Path script = Path.of("..", "stack.sh").toAbsolutePath().normalize();
      Process process = new ProcessBuilder(script.toString(), "env", stack).start();
      String out = new String(process.getInputStream().readAllBytes());
      if (process.waitFor() != 0) {
        throw new IllegalStateException("Stack '" + stack + "' is not up. Run: ./stack.sh up " + stack);
      }
      Map<String, String> vars = new HashMap<>();
      out.lines().map(l -> l.replaceFirst("^export ", "").split("=", 2))
          .filter(kv -> kv.length == 2)
          .forEach(kv -> vars.put(kv[0], kv[1]));
      return vars;
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Set BASE_URL and DB_URL, or bring up a stack with ./stack.sh up " + stack, e);
    }
  }
}
