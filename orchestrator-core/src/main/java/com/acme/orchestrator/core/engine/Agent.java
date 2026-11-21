package com.acme.orchestrator.core.engine;

/**
 * Port: does a stage's work. Runs on a worker (virtual) thread, possibly alongside other agents.
 * Must not touch run state; everything it produces goes back in the {@link AgentResult}.
 */
@FunctionalInterface
public interface Agent {

  /** Throwing is allowed and counts as a failure of this attempt. */
  AgentResult execute(StageContext context) throws Exception;
}
