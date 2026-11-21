package com.acme.orchestrator.agents.tools;

import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.policy.Decision;
import com.acme.orchestrator.core.policy.ProposedAction;

/** Asks the policy engine about an action; in a run, {@link StageContext#authorize} (audited). */
@FunctionalInterface
public interface Authorizer {

  Decision authorize(ProposedAction action);

  static Authorizer of(StageContext ctx) {
    return ctx::authorize;
  }
}
