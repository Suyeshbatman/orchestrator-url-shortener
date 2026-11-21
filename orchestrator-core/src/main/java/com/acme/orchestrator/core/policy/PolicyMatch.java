package com.acme.orchestrator.core.policy;

import java.util.Objects;

/**
 * A rule that applied to an action. Reasons never quote matched content (a secret that tripped
 * SEC-001 must not end up in the audit log).
 */
public record PolicyMatch(String policyId, Effect effect, String reason) {

  public PolicyMatch {
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(reason, "reason");
  }
}
