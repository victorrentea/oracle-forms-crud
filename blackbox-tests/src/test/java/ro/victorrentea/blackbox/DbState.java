package ro.victorrentea.blackbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/** The content of the application's tables as text, the "after" picture of a golden master. */
final class DbState {
  private DbState() {
  }

  static String dump(Connection connection) throws SQLException {
    return table(connection, "CUSTOMERS", "select * from customers order by id")
           + table(connection, "ORDERS", "select * from orders order by id")
           + table(connection, "AUDIT_LOG", "select * from audit_log order by id");
  }

  private static String table(Connection connection, String title, String sql) throws SQLException {
    StringBuilder out = new StringBuilder("== ").append(title).append(" ==\n");
    try (var statement = connection.createStatement();
         ResultSet rs = statement.executeQuery(sql)) {
      int columns = rs.getMetaData().getColumnCount();
      for (int i = 1; i <= columns; i++) {
        out.append(i > 1 ? " | " : "").append(rs.getMetaData().getColumnName(i));
      }
      out.append('\n');
      while (rs.next()) {
        for (int i = 1; i <= columns; i++) {
          out.append(i > 1 ? " | " : "").append(rs.getString(i));
        }
        out.append('\n');
      }
    }
    return out.append('\n').toString();
  }
}
