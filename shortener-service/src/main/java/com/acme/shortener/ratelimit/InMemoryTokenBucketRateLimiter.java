package com.acme.shortener.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;

/**
 * Per-instance buckets, used for create when Redis is unavailable (N instances allow up to N times
 * the limit). Bounded by Caffeine so unique keys can't grow memory without limit. A bucket idle for
 * a full period would be full again anyway, so evicting it loses nothing.
 */
public final class InMemoryTokenBucketRateLimiter implements RateLimiter {

  private final Clock clock;
  private final Cache<String, TokenBucket> buckets;

  public InMemoryTokenBucketRateLimiter(Clock clock, long maxKeys, Duration idleExpiry) {
    this.clock = clock;
    this.buckets =
        Caffeine.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterAccess(idleExpiry)
            .executor(Runnable::run) // evict on the caller thread; no background pool needed
            .build();
  }

  @Override
  public Decision tryConsume(String key, Limit limit) {
    long now = clock.millis();
    return buckets.get(key, k -> new TokenBucket(limit, now)).tryConsume(now);
  }

  long size() {
    buckets.cleanUp();
    return buckets.estimatedSize();
  }
}
