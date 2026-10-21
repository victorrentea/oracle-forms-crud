package ro.victorrentea.blackbox;

import org.approvaltests.core.ApprovalFailureReporter;
import org.approvaltests.reporters.QuietReporter;

/** ApprovalTests config for this package: never pop up a diff tool, keep golden files in approved/. */
public class PackageSettings {
  public static ApprovalFailureReporter FrontloadedReporter = new QuietReporter();
  public static String UseApprovalSubdirectory = "approved";
}
