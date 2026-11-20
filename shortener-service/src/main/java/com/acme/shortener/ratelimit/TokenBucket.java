package com.acme.shortener.ratelimit;

/**
 * One in-process bucket. Two numbers of state, O(1) per check. {@code token-bucket.lua} is the same
 * algorithm on the Redis side; keep the two in step.
 */
final class TokenBucket {

  private final Limit limit;
  private double tokens;
  private long lastRefillMillis;

  TokenBucket(Limit limit, long nowMillis) {
    this.limit = limit;
    this.tokens = limit.capacity();
    this.lastRefillMillis = nowMillis;
  }

  synchronized Decision tryConsume(long nowMillis) {
    // A clock that steps backwards never refills and never moves the timestamp back.
    if (nowMillis > lastRefillMillis) {
      double refill = (nowMillis - lastRefillMillis) * limit.tokensPerMilli();
      tokens = Math.min(limit.capacity(), tokens + refill);
      lastRefillMillis = nowMillis;
    }
    boolean allowed = tokens >= 1;
    if (allowed) {
      tokens -= 1;
    }
    return Decision.of(limit, allowed, tokens);
  }
}
