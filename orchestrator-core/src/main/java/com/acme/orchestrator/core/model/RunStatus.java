package com.acme.orchestrator.core.model;

public enum RunStatus {
  CREATED,
  RUNNING,
  /** Blocked on a human approval. */
  AWAITING_HUMAN,
  COMPLETED,
  FAILED,
  /** Safe-stopped; can be resumed. */
  HALTED,
  ROLLED_BACK;

  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED || this == ROLLED_BACK;
  }
}
