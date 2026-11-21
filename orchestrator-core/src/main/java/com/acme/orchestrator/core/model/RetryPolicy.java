package com.acme.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;

/**
 * @param maxAttempts total attempts including the first (1 = no retry)
 */
public record RetryPolicy(
    int maxAttempts, Backoff backoff, Duration baseDelay, Duration maxDelay, boolean jitter) {

  public enum Backoff {
    FIXED,
    EXPONENTIAL
  }

  /** Used when neither the stage nor the workflow defaults say otherwise. */
  public static final RetryPolicy NONE =
      new RetryPolicy(1, Backoff.EXPONENTIAL, Duration.ofSeconds(2), Duration.ofSeconds(20), true);

  public RetryPolicy {
    Objects.requireNonNull(backoff, "backoff");
    Objects.requireNonNull(baseDelay, "baseDelay");
    Objects.requireNonNull(maxDelay, "maxDelay");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("need 0 <= baseDelay <= maxDelay");
    }
  }
}
