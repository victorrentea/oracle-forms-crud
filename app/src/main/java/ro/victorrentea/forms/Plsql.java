package ro.victorrentea.forms;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;

/** The only way the UI changes data: calling a package procedure. No business rules in Java. */
@Component
public class Plsql {
  private final JdbcTemplate jdbc;

  public Plsql(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Calls a procedure whose parameters are all IN. */
  public void call(String procedure, Object... args) {
    execute(procedure, args, false);
  }

  /** Calls a procedure whose last parameter is an OUT id. */
  public long callReturningId(String procedure, Object... args) {
    return execute(procedure, args, true);
  }

  private long execute(String procedure, Object[] args, boolean returnsId) {
    int paramCount = args.length + (returnsId ? 1 : 0);
    String sql = "{call " + procedure + "(" + "?,".repeat(paramCount).replaceAll(",$", "") + ")}";
    try {
      return jdbc.execute(sql, (CallableStatement cs) -> {
        for (int i = 0; i < args.length; i++) {
          cs.setObject(i + 1, args[i]);
        }
        if (returnsId) {
          cs.registerOutParameter(paramCount, Types.NUMERIC);
        }
        cs.execute();
        return returnsId ? cs.getLong(paramCount) : 0L;
      });
    } catch (DataAccessException e) {
      throw translate(e);
    }
  }

  /** ORA-20000..20999 are messages meant for the user; anything else is a real failure. */
  private static RuntimeException translate(DataAccessException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getErrorCode() >= 20000 && sql.getErrorCode() <= 20999) {
        return new FormsError(sql.getMessage().lines().findFirst().orElse("").trim());
      }
    }
    return e;
  }
}
