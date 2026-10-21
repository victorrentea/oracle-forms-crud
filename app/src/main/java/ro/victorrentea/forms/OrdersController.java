package ro.victorrentea.forms;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static ro.victorrentea.forms.Fields.number;

@Controller
public class OrdersController {
  static final String SAVED = "FRM-40400: Transaction complete: 1 records applied and saved.";

  public record OrderRow(long id, long customerId, String customerName, String product, int quantity,
                         BigDecimal unitPrice, BigDecimal discountPct, BigDecimal total, String status,
                         LocalDateTime createdAt, LocalDateTime updatedAt) {
  }

  public record OrderForm(Long id, Long customerId, String product, String quantity, String unitPrice) {
  }

  public record Lov(long id, String name) {
  }

  private final NamedParameterJdbcTemplate jdbc;
  private final Plsql plsql;

  public OrdersController(NamedParameterJdbcTemplate jdbc, Plsql plsql) {
    this.jdbc = jdbc;
    this.plsql = plsql;
  }

  @GetMapping("/")
  public String home() {
    return "redirect:/orders";
  }

  // ---- query mode ----

  @GetMapping("/orders")
  public String query(@RequestParam(required = false) Long customerId,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) String product,
                      Model model) {
    String sql = """
        select * from v_orders
         where (:customerId is null or customer_id = :customerId)
           and (:status is null or status = :status)
           and (:product is null or upper(product) like '%' || upper(:product) || '%')
         order by id""";
    var params = new MapSqlParameterSource()
        .addValue("customerId", customerId)
        .addValue("status", blankToNull(status))
        .addValue("product", blankToNull(product));
    model.addAttribute("orders", jdbc.query(sql, params, new DataClassRowMapper<>(OrderRow.class)));
    model.addAttribute("customers", customerLov(false));
    model.addAttribute("filter", new OrderForm(null, customerId, product, null, null));
    model.addAttribute("status", status);
    return "orders";
  }

  // ---- the form ----

  @GetMapping("/orders/new")
  public String newOrder(Model model) {
    return showForm(model, new OrderForm(null, null, "", "", ""), null);
  }

  @PostMapping("/orders")
  public String create(OrderForm form, Model model, RedirectAttributes redirect) {
    try {
      long id = plsql.callReturningId("orders_api.create_order",
          form.customerId(), form.product(), number("Quantity", form.quantity()), number("Unit Price", form.unitPrice()));
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/orders/" + id;
    } catch (FormsError e) {
      model.addAttribute("error", e.getMessage());
      return showForm(model, form, null);
    }
  }

  @GetMapping("/orders/{id}")
  public String edit(@PathVariable long id, Model model) {
    OrderRow order = find(id);
    if (order == null) {
      model.addAttribute("error", "FRM-40350: Query caused no records to be retrieved.");
      return "not-found";
    }
    var form = new OrderForm(id, order.customerId(), order.product(),
        String.valueOf(order.quantity()), order.unitPrice().toPlainString());
    return showForm(model, form, order);
  }

  @PostMapping("/orders/{id}")
  public String update(@PathVariable long id, OrderForm form, Model model, RedirectAttributes redirect) {
    try {
      plsql.call("orders_api.update_order",
          id, form.product(), number("Quantity", form.quantity()), number("Unit Price", form.unitPrice()));
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/orders/" + id;
    } catch (FormsError e) {
      model.addAttribute("error", e.getMessage());
      return showForm(model, form, find(id));
    }
  }

  @PostMapping("/orders/{id}/ship")
  public String ship(@PathVariable long id, RedirectAttributes redirect) {
    return action(redirect, "redirect:/orders/" + id, () -> plsql.call("orders_api.ship_order", id));
  }

  @PostMapping("/orders/{id}/cancel")
  public String cancel(@PathVariable long id, RedirectAttributes redirect) {
    return action(redirect, "redirect:/orders/" + id, () -> plsql.call("orders_api.cancel_order", id));
  }

  @PostMapping("/orders/{id}/delete")
  public String delete(@PathVariable long id, RedirectAttributes redirect) {
    try {
      plsql.call("orders_api.delete_order", id);
      redirect.addFlashAttribute("message", SAVED);
      return "redirect:/orders";
    } catch (FormsError e) {
      redirect.addFlashAttribute("error", e.getMessage());
      return "redirect:/orders/" + id;
    }
  }

  private String action(RedirectAttributes redirect, String target, Runnable call) {
    try {
      call.run();
      redirect.addFlashAttribute("message", SAVED);
    } catch (FormsError e) {
      redirect.addFlashAttribute("error", e.getMessage());
    }
    return target;
  }

  private String showForm(Model model, OrderForm form, OrderRow saved) {
    model.addAttribute("form", form);
    model.addAttribute("order", saved);
    model.addAttribute("customers", customerLov(true));
    return "order";
  }

  private OrderRow find(long id) {
    return jdbc.query("select * from v_orders where id = :id", new MapSqlParameterSource("id", id),
        new DataClassRowMapper<>(OrderRow.class)).stream().findFirst().orElse(null);
  }

  private List<Lov> customerLov(boolean onlyActive) {
    return jdbc.query("select id, name from customers where (:all = 1 or active = 'Y') order by name",
        new MapSqlParameterSource("all", onlyActive ? 0 : 1), new DataClassRowMapper<>(Lov.class));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
