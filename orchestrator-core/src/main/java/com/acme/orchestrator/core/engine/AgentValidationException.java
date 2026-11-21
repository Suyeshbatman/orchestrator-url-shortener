package com.acme.orchestrator.core.engine;

/**
 * Thrown by an agent whose output is not acceptable, e.g. LLM output that still fails its JSON
 * Schema after the repair attempt. A {@link FailureKind#VALIDATION} failure: retried with the
 * message as feedback.
 */
public class AgentValidationException extends RuntimeException {

  public AgentValidationException(String message) {
    super(message);
  }
}
