package com.acme.orchestrator.core.checkpoint;

import java.util.Objects;

/**
 * A saved state of the run's workspace, e.g. a Git commit on the agent branch.
 *
 * @param label what it was taken for: the stage it precedes, or {@code safe-stop}
 */
public record CheckpointRef(String id, String runId, String label) {

  public CheckpointRef {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(label, "label");
  }
}
