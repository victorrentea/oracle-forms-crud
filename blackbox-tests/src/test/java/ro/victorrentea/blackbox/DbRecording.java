package ro.victorrentea.blackbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A .rec file written by ../capture.sh: one statement per line, "sql TAB binds".
 * Binds come as Oracle's unified audit prints them: "#1(4):Bolt #2(0): #3(1):3"
 * where (n) is the length of the value, so values may hold spaces.
 */
record DbRecording(String name, List<Statement> statements) {

  record Statement(String sql, List<String> binds) {
  }

  private static final Pattern BIND_HEADER = Pattern.compile("#(\\d+)\\((\\d+)\\):");

  static DbRecording read(Path file) throws IOException {
    List<Statement> statements = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\t", 2);
      statements.add(new Statement(unescape(parts[0]).trim(), parts.length < 2 ? List.of() : parseBinds(unescape(parts[1]))));
    }
    return new DbRecording(file.getFileName().toString(), statements);
  }

  /** Values by position; null where nothing was bound (an OUT parameter) or the value was empty (Oracle NULL). */
  static List<String> parseBinds(String text) {
    List<String> values = new ArrayList<>();
    Matcher header = BIND_HEADER.matcher(text);
    int from = 0;
    while (header.find(from)) {
      int position = Integer.parseInt(header.group(1));
      int length = Integer.parseInt(header.group(2));
      String value = text.substring(header.end(), Math.min(text.length(), header.end() + length));
      while (values.size() < position) {
        values.add(null);
      }
      values.set(position - 1, value.isEmpty() ? null : value);
      from = header.end() + length;
    }
    return values;
  }

  private static String unescape(String s) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        out.append(switch (next) {
          case 'n' -> '\n';
          case 'r' -> '\r';
          case 't' -> '\t';
          default -> next;
        });
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
