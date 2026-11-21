package com.acme.orchestrator.core.policy;

/**
 * Rules no autonomy level or config can relax. They are enforced in code; {@code autonomy.yml} must
 * list exactly these (so the file can't claim a rule the engine doesn't enforce, or silently drop
 * one).
 */
public enum Invariant {
  /**
   * Enforced by {@link PolicyEngine#evaluate} (Publish) and {@link
   * PolicyEngine#stageApprovalRequired}.
   */
  PUBLISH_REQUIRES_APPROVAL("publish requires human approval"),
  /** Enforced by combining effects with {@link Effect#strictest}: nothing lowers a DENY. */
  DENY_IS_FINAL("DENY policies are never overridable"),
  /** Enforced when approvals are recorded (Step 4.3). */
  HUMAN_APPROVER("approvals must come from a human principal with role APPROVER");

  private final String text;

  Invariant(String text) {
    this.text = text;
  }

  /** The wording in {@code autonomy.yml}. */
  public String text() {
    return text;
  }
}
