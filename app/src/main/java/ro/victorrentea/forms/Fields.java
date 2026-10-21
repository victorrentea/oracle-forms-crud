package ro.victorrentea.forms;

import java.math.BigDecimal;

/** Field-level format checks, the part Forms itself did before any trigger fired. */
final class Fields {
  private Fields() {
  }

  static BigDecimal number(String label, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      throw new FormsError("FRM-50016: Legal characters are 0-9 - + E . (field " + label + ")");
    }
  }
}
