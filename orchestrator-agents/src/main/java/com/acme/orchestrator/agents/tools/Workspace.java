package com.acme.orchestrator.agents.tools;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A run's private copy of the repository: a clone at {@code ORCH_WORKSPACE_ROOT/<runId>} on branch
 * {@code agent/<runId>}, created from the base branch's head.
 *
 * @param baseCommit the commit the branch started from (diffs are against it)
 */
public record Workspace(String runId, Path root, String branch, String baseCommit) {

  public Workspace {
    Objects.requireNonNull(runId, "runId");
    root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(baseCommit, "baseCommit");
  }
}
