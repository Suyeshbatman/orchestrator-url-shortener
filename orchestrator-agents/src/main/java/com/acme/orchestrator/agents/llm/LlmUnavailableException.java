package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.core.engine.TransientAgentException;

/** The model couldn't be reached or kept failing (429, 5xx, timeouts, circuit open). Transient. */
public final class LlmUnavailableException extends TransientAgentException {

  public LlmUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
