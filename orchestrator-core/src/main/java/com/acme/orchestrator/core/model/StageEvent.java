package com.acme.orchestrator.core.model;

/** What happens to a stage. The engine never sets a {@link StageStatus} directly. */
public enum StageEvent {
  /** Every dependency is SUCCEEDED or SKIPPED. */
  DEPENDENCIES_MET,
  /** The condition evaluated to false. */
  SKIP,
  /** An attempt starts (first, or after a retry backoff). */
  START,
  /** Exit gates passed and the stage needs human approval. */
  AWAIT_APPROVAL,
  APPROVE,
  REJECT,
  /** Exit gates passed and no approval is needed. */
  SUCCEED,
  /** The attempt failed and attempts remain. */
  SCHEDULE_RETRY,
  /** Failed for good: an entry gate, or attempts and fallback exhausted. */
  FAIL,
  /** A re-plan or loop-back discards this stage's output. */
  INVALIDATE,
  /** Run it again from the start (after invalidation, or when resuming a halted run). */
  RESET,
  /** Its changes are undone by restoring its checkpoint. */
  ROLL_BACK,
  /** Safe stop. */
  HALT
}
