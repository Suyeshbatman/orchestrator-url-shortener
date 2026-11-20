package com.acme.shortener.ratelimit;

import java.time.Duration;

/**
 * A token bucket's shape: bursts of up to {@code capacity} requests, refilling continuously at
 * {@code capacity / period} (DESIGN_RATIONALE §3.4).
 */
public record Limit(long capacity, Duration period) {

  public Limit {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1");
    }
    if (period.toMillis() < 1) {
      throw new IllegalArgumentException("period must be at least 1ms");
    }
  }

  double tokensPerMilli() {
    return (double) capacity / period.toMillis();
  }
}
