package ro.victorrentea.forms;

/** A message for the status bar: an ORA-200xx raised by the PL/SQL, or a field format error. */
public class FormsError extends RuntimeException {
  public FormsError(String message) {
    super(message);
  }
}
