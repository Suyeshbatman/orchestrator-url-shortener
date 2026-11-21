package com.acme.orchestrator.core.policy;

/** What a policy says about an action, from least to most restrictive. */
public enum Effect {
  ALLOW,
  REQUIRE_APPROVAL,
  DENY;

  /** The stricter of the two: combining effects can only tighten. */
  public Effect strictest(Effect other) {
    return compareTo(other) >= 0 ? this : other;
  }
}
