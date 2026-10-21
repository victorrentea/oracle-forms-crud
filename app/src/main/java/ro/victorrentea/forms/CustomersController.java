package ro.victorrentea.forms;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

import static ro.victorrentea.forms.Fields.number;
import static ro.victorrentea.forms.OrdersController.SAVED;

@Controller
public class CustomersController {
  public record CustomerRow(long id, String name, BigDecimal creditLimit, String active, BigDecimal exposure) {
  }

  public record CustomerForm(Long id, String name, String creditLimit, String active) {
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final Plsql plsql;

  public CustomersController(NamedParameterJdbcTemplate jdbc, Plsql plsql) {
    this.jdbc = jdbc;
    this.plsql = plsql;
  }

  @GetMapping("/customers")
  public String list(Model model) {
    model.addAttribute("customers", jdbc.query("""
        select c.id, c.name, c.credit_limit, c.active, orders_api.exposure(c.id) exposure
          from customers c
         order by c.name""", new DataClassRowMapper<>(CustomerRow.class)));
    return "customers";
  }

  @GetMapping("/customers/new")
  public String newCustomer(Model model) {
    model.addAttribute("form", new CustomerForm(null, "", "", "Y"));
    return "customer";
  }

  @PostMapping("/customers")
  public String create(CustomerForm form, Model model, RedirectAttributes redirect) {
    try {
      long id = plsql.callReturningId("customers_api.create_customer", form.name(), number("Credit Limit", form.creditLimit()));
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/customers/" + id;
    } catch (FormsError e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("form", form);
      return "customer";
    }
  }

  @GetMapping("/customers/{id}")
  public String edit(@PathVariable long id, Model model) {
    var found = jdbc.query("select id, name, credit_limit, active from customers where id = :id",
        new MapSqlParameterSource("id", id), (rs, i) -> new CustomerForm(rs.getLong("id"), rs.getString("name"),
            rs.getBigDecimal("credit_limit").toPlainString(), rs.getString("active")));
    if (found.isEmpty()) {
      model.addAttribute("error", "FRM-40350: Query caused no records to be retrieved.");
      return "not-found";
    }
    model.addAttribute("form", found.get(0));
    return "customer";
  }

  @PostMapping("/customers/{id}")
  public String update(@PathVariable long id, CustomerForm form, Model model, RedirectAttributes redirect) {
    try {
      plsql.call("customers_api.update_customer", id, form.name(), number("Credit Limit", form.creditLimit()),
          form.active() == null ? "N" : form.active());
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/customers/" + id;
    } catch (FormsError e) {
      model.addAttribute("error", e.getMessage());
      model.addAttribute("form", new CustomerForm(id, form.name(), form.creditLimit(), form.active()));
      return "customer";
    }
  }

  @PostMapping("/customers/{id}/delete")
  public String delete(@PathVariable long id, RedirectAttributes redirect) {
    try {
      plsql.call("customers_api.delete_customer", id);
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/customers";
    } catch (FormsError e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/customers/" + id;
    }
  }
}
