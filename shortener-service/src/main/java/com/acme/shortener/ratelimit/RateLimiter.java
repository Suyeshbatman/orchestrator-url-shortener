package com.acme.shortener.ratelimit;

/** Port: a keyed token bucket. */
public interface RateLimiter {

  /** Takes one token from bucket {@code key} if it has one. A new bucket starts full. */
  Decision tryConsume(String key, Limit limit);
}
