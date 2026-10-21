package ro.victorrentea.blackbox;

import org.approvaltests.Approvals;
import org.approvaltests.integrations.junit5.JupiterApprovals;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Golden master below the UI. Each recording in db-recordings/ is what real users made the
 * application send to the database (captured with ../capture.sh, whatever the client: this web
 * app or an Oracle Forms runtime). Replay it from the same starting data and compare what each
 * call answered plus the final content of the tables with the approved version.
 * Refactor the PL/SQL freely; this test tells you if any recorded behaviour changed.
 */
class DbReplayGoldenMasterTest {
  static final Path RECORDINGS = Path.of("src/test/resources/db-recordings");
  // :1 or :name, but not := nor anything inside a 'string literal'
  private static final Pattern PLACEHOLDER = Pattern.compile("'(?:[^']|'')*'|(?<![\\w:]):([A-Za-z_]\\w*|\\d+)");

  @TestFactory
  Stream<DynamicTest> replayEachRecording() throws IOException {
    try (var files = Files.list(RECORDINGS)) {
      return files.filter(f -> f.toString().endsWith(".rec")).sorted().toList().stream()
          .map(file -> JupiterApprovals.dynamicTest(file.getFileName().toString().replace(".rec", ""),
              options -> Approvals.verify(replay(file), Golden.scrubbed(options))));
    }
  }

  static String replay(Path file) {
    try {
      return replay(DbRecording.read(file));
    } catch (IOException | SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String replay(DbRecording recording) throws SQLException {
    Stack.resetData();
    StringBuilder out = new StringBuilder();
    try (Connection connection = Stack.connect()) {
      connection.createStatement().execute("alter session set nls_numeric_characters = '.,'");
      int step = 0;
      for (DbRecording.Statement statement : recording.statements()) {
        out.append(String.format("%3d. %s %s%n     → %s%n",
            ++step, statement.sql().replace('\n', ' '), shown(statement.binds()), execute(connection, statement)));
      }
      out.append('\n').append(DbState.dump(connection));
    }
    return out.toString();
  }

  /** Runs one recorded statement; placeholders with no recorded value are OUT parameters. */
  private static String execute(Connection connection, DbRecording.Statement statement) {
    boolean plsql = statement.sql().regionMatches(true, 0, "BEGIN", 0, 5)
                    || statement.sql().regionMatches(true, 0, "DECLARE", 0, 7);
    // the audit numbers binds in order of appearance; a PL/SQL block binds each name once
    Collection<String> placeholders = plsql ? new LinkedHashSet<>() : new ArrayList<>();
    Matcher m = PLACEHOLDER.matcher(statement.sql());
    while (m.find()) {
      if (m.group(1) != null) {
        placeholders.add(m.group(1).toUpperCase());
      }
    }
    List<Integer> outs = new ArrayList<>();
    try (PreparedStatement ps = plsql ? connection.prepareCall(statement.sql()) : connection.prepareStatement(statement.sql())) {
      for (int position = 1; position <= placeholders.size(); position++) {
        String value = position <= statement.binds().size() ? statement.binds().get(position - 1) : null;
        if (value == null && plsql && position > statement.binds().size()) {
          ((CallableStatement) ps).registerOutParameter(position, Types.VARCHAR);
          outs.add(position);
        } else {
          ps.setString(position, value);
        }
      }
      ps.execute();
      int rows = plsql ? -1 : ps.getUpdateCount();
      StringBuilder result = new StringBuilder("OK");
      if (rows >= 0) {
        result.append(", ").append(rows).append(" row(s)");
      }
      for (int out : outs) {
        result.append(", out :").append(out).append(" = ").append(((CallableStatement) ps).getString(out));
      }
      return result.toString();
    } catch (SQLException e) {
      return e.getMessage().lines().findFirst().orElse("").trim();
    }
  }

  private static String shown(List<String> binds) {
    return binds.isEmpty() ? "" : binds.stream().map(b -> b == null ? "NULL" : "'" + b + "'").toList().toString();
  }
}
