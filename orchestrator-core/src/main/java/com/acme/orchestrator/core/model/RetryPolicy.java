package com.acme.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

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

  /**
   * How long to wait before the next attempt, after {@code attemptsUsed} (≥ 1) have failed: {@code
   * baseDelay} (fixed) or {@code baseDelay · 2^(attemptsUsed-1)} (exponential), capped at {@code
   * maxDelay}; with jitter, a uniformly random duration between zero and that ("full jitter", so
   * retries from many runs don't arrive together).
   */
  public Duration delayAfter(int attemptsUsed, RandomGenerator random) {
    if (attemptsUsed < 1) {
      throw new IllegalArgumentException("attemptsUsed must be at least 1");
    }
    long base = baseDelay.toMillis();
    long cap = maxDelay.toMillis();
    long millis;
    if (backoff == Backoff.FIXED) {
      millis = base;
    } else {
      long factor = 1L << Math.min(attemptsUsed - 1, 30);
      millis = base > cap / factor ? cap : base * factor; // no overflow
    }
    if (jitter && millis > 0) {
      millis = random.nextLong(millis + 1);
    }
    return Duration.ofMillis(millis);
  }
}
