package com.acme.orchestrator.core.engine;

import java.util.List;
import java.util.Objects;

/** What an agent hands back. Throwing an exception counts as a {@link Failure}. */
public sealed interface AgentResult {

  /**
   * @param summary one line for the audit log and approvers
   */
  record Success(List<ArtifactOutput> artifacts, String summary) implements AgentResult {
    public Success {
      artifacts = List.copyOf(artifacts);
      summary = summary == null ? "" : summary;
    }
  }

  record Failure(String reason) implements AgentResult {
    public Failure {
      Objects.requireNonNull(reason, "reason");
    }
  }

  static Success success(String summary, ArtifactOutput... artifacts) {
    return new Success(List.of(artifacts), summary);
  }

  static Failure failure(String reason) {
    return new Failure(reason);
  }
}
