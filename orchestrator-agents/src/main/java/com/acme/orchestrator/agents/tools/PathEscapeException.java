package com.acme.orchestrator.agents.tools;

/** A file path that would leave the workspace. Fatal: the agent asked for something forbidden. */
public final class PathEscapeException extends IllegalArgumentException {

  public PathEscapeException(String message) {
    super(message);
  }
}
