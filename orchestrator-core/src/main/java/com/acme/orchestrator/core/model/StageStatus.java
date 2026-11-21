package com.acme.orchestrator.core.model;

/** Lifecycle of one stage (or expanded task) in a run. Moves only via {@link StageStateMachine}. */
public enum StageStatus {
  /** Waiting for its dependencies. */
  PENDING,
  /** Dependencies satisfied; can be scheduled. */
  READY,
  RUNNING,
  /** Exit gates passed; a human must approve before it counts as succeeded. */
  WAITING_APPROVAL,
  SUCCEEDED,
  FAILED,
  /** Failed an attempt; another attempt is scheduled (with backoff). */
  RETRYING,
  /** Condition was false. Counts as satisfied for dependents. */
  SKIPPED,
  /** Output discarded by a re-plan or a loop-back; will be reset and run again. */
  INVALIDATED,
  /** Its changes were undone by restoring a checkpoint. */
  ROLLED_BACK,
  /** Safe-stopped (budget, policy, high-impact failure, operator). Resumable. */
  HALTED;

  /** Satisfies a dependency: dependents may become READY. */
  public boolean satisfiesDependency() {
    return this == SUCCEEDED || this == SKIPPED;
  }

  /** No further transition happens without an outside action (re-plan, resume, rollback). */
  public boolean isSettled() {
    return switch (this) {
      case SUCCEEDED, FAILED, SKIPPED, ROLLED_BACK, HALTED -> true;
      default -> false;
    };
  }
}
