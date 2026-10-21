package ro.victorrentea.forms;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;

@Controller
public class AuditController {
  public record AuditRow(long id, String tableName, long rowId, String action, String oldValues, String newValues,
                         LocalDateTime changedAt, String changedBy) {
  }

  private final JdbcTemplate jdbc;

  public AuditController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/audit")
  public String audit(Model model) {
    model.addAttribute("rows", jdbc.query("select * from audit_log order by id", new DataClassRowMapper<>(AuditRow.class)));
    return "audit";
  }
}
