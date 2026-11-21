package com.acme.orchestrator.core.model;

import java.util.Objects;

/** One execution of a workflow. Immutable; changes produce a new value. */
public record Run(
    String id,
    String workflowId,
    RunMode mode,
    AutonomyLevel autonomyLevel,
    RunStatus status,
    Budgets budgets) {

  public Run {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(workflowId, "workflowId");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(autonomyLevel, "autonomyLevel");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(budgets, "budgets");
  }

  public static Run create(
      String id, WorkflowDefinition workflow, RunMode mode, AutonomyLevel autonomyLevel) {
    return new Run(id, workflow.id(), mode, autonomyLevel, RunStatus.CREATED, workflow.budgets());
  }

  public Run withStatus(RunStatus next) {
    return new Run(id, workflowId, mode, autonomyLevel, next, budgets);
  }
}
