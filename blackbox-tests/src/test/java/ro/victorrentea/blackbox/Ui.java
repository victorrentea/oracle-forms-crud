package ro.victorrentea.blackbox;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A user at the keyboard, minus the browser: opens screens, types into fields found by their
 * label, presses buttons found by their caption. It knows no URLs beyond the menu entries and
 * nothing about what runs behind the screens.
 */
public class Ui {
  private final HttpClient http = HttpClient.newBuilder()
      .cookieHandler(new CookieManager())
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final List<String> journal = new ArrayList<>();

  /** Opens a screen from the menu: "Orders", "Customers" or "Audit Log". */
  public Screen menu(String entry) {
    Screen any = open("/");
    Element link = any.page().select("nav a").stream().filter(a -> a.text().equals(entry)).findFirst()
        .orElseThrow(() -> new AssertionError("No menu entry " + entry));
    return open(link.attr("href"));
  }

  public Screen open(String path) {
    return send("GET", path, Map.of());
  }

  /** Follows a link (e.g. a record id in a grid, or "New Order") by its visible text. */
  public Screen click(Screen screen, String linkText) {
    Element link = screen.page().select("main a").stream().filter(a -> a.text().equals(linkText)).findFirst()
        .orElseThrow(() -> new AssertionError("No link '" + linkText + "' on " + screen.path()));
    return open(link.attr("href"));
  }

  public Typing on(Screen screen) {
    return new Typing(screen);
  }

  /** The steps taken so far, one line each: what a recording of this session would hold. */
  public List<String> journal() {
    return journal;
  }

  public class Typing {
    private final Screen screen;
    private final Map<String, String> typed = new LinkedHashMap<>();

    private Typing(Screen screen) {
      this.screen = screen;
    }

    /** Types into a text field, picks a visible choice in a list, or sets a checkbox to Y/N. */
    public Typing fill(String label, String value) {
      screen.input(label); // fail fast on an unknown label
      typed.put(label, value);
      return this;
    }

    public Screen press(String buttonCaption) {
      Element button = screen.page().select("button").stream()
          .filter(b -> b.text().equals(buttonCaption)).findFirst()
          .orElseThrow(() -> new AssertionError("No button '" + buttonCaption + "' on " + screen.path()));
      Element form = button.closest("form");
      Map<String, String> params = new LinkedHashMap<>();
      for (Element input : form.select("input[name], select[name]")) {
        if ("checkbox".equals(input.attr("type")) && !input.hasAttr("checked")) {
          continue;
        }
        params.put(input.attr("name"), input.tagName().equals("select") ? selected(input) : input.val());
      }
      typed.forEach((label, value) -> {
        Element input = screen.input(label);
        if (!form.select("#" + input.id()).contains(input)) {
          throw new AssertionError("Field '" + label + "' is not part of the form of button " + buttonCaption);
        }
        if (input.tagName().equals("select")) {
          params.put(input.attr("name"), value.isEmpty() ? "" : screen.optionValue(label, value));
        } else if ("checkbox".equals(input.attr("type"))) {
          if ("Y".equals(value)) {
            params.put(input.attr("name"), input.val());
          } else {
            params.remove(input.attr("name"));
          }
        } else {
          params.put(input.attr("name"), value);
        }
      });
      String method = form.attr("method").isEmpty() ? "GET" : form.attr("method").toUpperCase();
      String action = form.attr("action").isEmpty() ? screen.path() : form.attr("action");
      return send(method, action, params);
    }

    private static String selected(Element select) {
      Element option = select.selectFirst("option[selected]");
      return option == null ? "" : option.val();
    }
  }

  private Screen send(String method, String path, Map<String, String> params) {
    String query = params.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
    journal.add(method + " " + path + (query.isEmpty() ? "" : " " + query));
    URI uri = URI.create(Stack.BASE_URL + path + ("GET".equals(method) && !query.isEmpty() ? "?" + query : ""));
    HttpRequest request = "GET".equals(method)
        ? HttpRequest.newBuilder(uri).GET().build()
        : HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new AssertionError(method + " " + path + " answered HTTP " + response.statusCode() + ":\n" + response.body());
      }
      return new Screen(response.uri().getPath(), Jsoup.parse(response.body(), response.uri().toString()));
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }
}
