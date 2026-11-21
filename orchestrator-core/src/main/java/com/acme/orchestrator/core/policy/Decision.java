package com.acme.orchestrator.core.policy;

import java.util.List;
import java.util.Objects;

/**
 * The verdict on a proposed action.
 *
 * @param matchedPolicies every rule that applied, including autonomy-level and invariant rules
 * @param riskScore 0–100: weights of the matched policies plus a size/impact factor
 */
public record Decision(Effect effect, List<PolicyMatch> matchedPolicies, int riskScore) {

  public Decision {
    Objects.requireNonNull(effect, "effect");
    matchedPolicies = List.copyOf(matchedPolicies);
    if (riskScore < 0 || riskScore > 100) {
      throw new IllegalArgumentException("riskScore must be 0-100, was " + riskScore);
    }
  }

  public boolean allowed() {
    return effect == Effect.ALLOW;
  }

  public boolean denied() {
    return effect == Effect.DENY;
  }
}
