package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.PolicyMatch;
import java.util.stream.Collectors;

/**
 * A tool refused an action the policy engine didn't allow. Fatal for the attempt (the decision is
 * already in the audit log as POLICY_EVALUATED).
 */
public final class PolicyDeniedException extends IllegalStateException {

  private final transient Decision decision;

  public PolicyDeniedException(String action, Decision decision) {
    super(
        action
            + " not allowed ("
            + decision.effect()
            + "): "
            + decision.matchedPolicies().stream()
                .map(PolicyMatch::reason)
                .collect(Collectors.joining("; ")));
    this.decision = decision;
  }

  public Decision decision() {
    return decision;
  }
}
