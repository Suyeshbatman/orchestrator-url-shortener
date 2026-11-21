package com.acme.orchestrator.core.engine;

/**
 * Thrown by an agent (or the LLM gateway under it) for a failure worth retrying later: a timeout,
 * rate limit or server error.
 */
public class TransientAgentException extends RuntimeException {

  public TransientAgentException(String message) {
    super(message);
  }

  public TransientAgentException(String message, Throwable cause) {
    super(message, cause);
  }
}
