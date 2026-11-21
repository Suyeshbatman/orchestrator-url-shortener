package com.acme.orchestrator.agents.llm;

/**
 * Replay mode has no recorded reply for a request. Fatal: retrying can't conjure a fixture. The
 * message says how to make one.
 */
public final class ReplayMissingException extends IllegalStateException {

  public ReplayMissingException(String message) {
    super(message);
  }
}
