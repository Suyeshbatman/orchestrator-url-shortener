package com.acme.orchestrator.core.event;

/** Everything that can happen in a run. The log of these is the source of truth (ADR-0007). */
public enum EventType {
  RUN_CREATED,
  STAGE_READY,
  STAGE_STARTED,
  AGENT_INVOKED,
  /** LLM calls and tokens an attempt used (summed for the budget). */
  USAGE_RECORDED,
  ARTIFACT_PRODUCED,
  GATE_EVALUATED,
  POLICY_EVALUATED,
  APPROVAL_REQUESTED,
  APPROVAL_GRANTED,
  APPROVAL_REJECTED,
  STAGE_SUCCEEDED,
  STAGE_FAILED,
  STAGE_RETRIED,
  FALLBACK_USED,
  CHECKPOINT_CREATED,
  ROLLED_BACK,
  REPLAN_TRIGGERED,
  STAGE_INVALIDATED,
  STAGE_SKIPPED,
  BUDGET_EXCEEDED,
  /** Safe stop begun: nothing new is dispatched; running stages get a grace period. */
  SAFE_STOP_REQUESTED,
  RUN_HALTED,
  RUN_RESUMED,
  RUN_COMPLETED,
  RUN_FAILED
}
