package com.acme.orchestrator.core.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One stage of a workflow, as declared. Workflow defaults (retry, timeout) are already applied.
 *
 * @param condition expression evaluated against the run (e.g. {@code run.mode == 'BROWNFIELD'});
 *     false means SKIPPED, which still satisfies dependents
 * @param expand what to fan out over at runtime ({@code tasks}: one task per task-graph node)
 * @param highImpact a failure halts the run instead of failing it
 */
public record StageDefinition(
    String id,
    Executor executor,
    List<String> dependsOn,
    Optional<String> condition,
    List<GateRef> entryGates,
    List<GateRef> exitGates,
    Approval approval,
    RetryPolicy retry,
    Optional<FallbackPolicy> fallback,
    CheckpointKind checkpoint,
    Optional<OnFailure> onFailure,
    Optional<String> expand,
    boolean highImpact,
    Duration timeout) {

  public StageDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(executor, "executor");
    dependsOn = List.copyOf(dependsOn);
    condition = Objects.requireNonNull(condition, "condition");
    entryGates = List.copyOf(entryGates);
    exitGates = List.copyOf(exitGates);
    Objects.requireNonNull(approval, "approval");
    Objects.requireNonNull(retry, "retry");
    fallback = Objects.requireNonNull(fallback, "fallback");
    Objects.requireNonNull(checkpoint, "checkpoint");
    onFailure = Objects.requireNonNull(onFailure, "onFailure");
    expand = Objects.requireNonNull(expand, "expand");
    Objects.requireNonNull(timeout, "timeout");
  }
}
