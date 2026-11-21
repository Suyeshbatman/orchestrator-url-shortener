package com.acme.orchestrator.core.model;

/** What to try once retries are exhausted, e.g. {@code minimal-patch}. */
public record FallbackPolicy(String strategy) {

  public FallbackPolicy {
    if (strategy == null || strategy.isBlank()) {
      throw new IllegalArgumentException("fallback strategy is required");
    }
  }
}
