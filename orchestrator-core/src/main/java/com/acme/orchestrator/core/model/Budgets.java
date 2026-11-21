package com.acme.orchestrator.core.model;

import java.time.Duration;
import java.util.Objects;

/** Hard caps per run. Exceeding any of them halts the run (bounded autonomy, no runaway loops). */
public record Budgets(Duration maxWallClock, int maxLlmCalls, long maxTokens) {

  public Budgets {
    Objects.requireNonNull(maxWallClock, "maxWallClock");
    if (maxWallClock.isNegative() || maxWallClock.isZero()) {
      throw new IllegalArgumentException("maxWallClock must be positive");
    }
    if (maxLlmCalls < 0 || maxTokens < 0) {
      throw new IllegalArgumentException("budgets must not be negative");
    }
  }
}
