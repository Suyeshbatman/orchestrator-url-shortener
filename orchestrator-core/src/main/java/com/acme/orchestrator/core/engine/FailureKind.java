package com.acme.orchestrator.core.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeoutException;

/** Why an attempt failed, which decides what happens next. */
public enum FailureKind {
  /** Infrastructure trouble (LLM timeout, 429, 5xx, I/O): retry after a backoff. */
  TRANSIENT,
  /** The work was not good enough (schema or gate failure): retry, loop back or fall back. */
  VALIDATION,
  /** Retrying can't help (policy DENY, missing inputs, illegal state): fail at once. */
  FATAL;

  public boolean retryable() {
    return this != FATAL;
  }

  /**
   * What an exception thrown by an agent means: {@link TransientAgentException}, timeouts and I/O
   * errors are transient; {@code IllegalStateException}/{@code IllegalArgumentException} (a bug or
   * a broken precondition) are fatal; anything else unknown gets the benefit of the doubt.
   */
  public static FailureKind of(Throwable error) {
    return switch (error) {
      case TransientAgentException e -> TRANSIENT;
      case TimeoutException e -> TRANSIENT;
      case IOException e -> TRANSIENT;
      case UncheckedIOException e -> TRANSIENT;
      case IllegalStateException e -> FATAL;
      case IllegalArgumentException e -> FATAL;
      default -> TRANSIENT;
    };
  }
}
