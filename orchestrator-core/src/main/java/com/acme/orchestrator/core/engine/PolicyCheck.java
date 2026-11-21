package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.util.Objects;

/** An action an agent asked about through {@link StageContext#authorize}, and the verdict. */
public record PolicyCheck(ProposedAction action, Decision decision) {

  public PolicyCheck {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(decision, "decision");
  }

  /**
   * One line for the audit log. Never includes file content or diff text: those may hold the very
   * secret a policy caught.
   */
  public String describe() {
    return switch (action) {
      case ProposedAction.FileWrite w -> "file_write " + w.path();
      case ProposedAction.Diff d ->
          "diff " + d.filesChanged() + " files, " + d.linesChanged() + " lines";
      case ProposedAction.Command c -> "command '" + abbreviate(c.cmdline()) + "'";
      case ProposedAction.Publish p ->
          p.description().isBlank() ? "publish" : "publish " + abbreviate(p.description());
    };
  }

  private static String abbreviate(String text) {
    String line = text.strip();
    return line.length() > 200 ? line.substring(0, 200) + "…" : line;
  }
}
