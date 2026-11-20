package com.acme.shortener.ratelimit;

import java.time.Duration;

/**
 * The outcome of one {@link RateLimiter#tryConsume} call.
 *
 * @param metered false when no limiter answered (redirect failing open); no headers are sent then
 * @param retryAfter time until one token is available; zero when allowed
 * @param resetAfter time until the bucket is full again
 */
public record Decision(
    boolean allowed,
    boolean metered,
    long limit,
    long remaining,
    Duration retryAfter,
    Duration resetAfter) {

  /** Let through without a limiter's answer. */
  public static final Decision UNMETERED =
      new Decision(true, false, 0, 0, Duration.ZERO, Duration.ZERO);

  /** Builds the decision from the bucket's token count after the attempt. */
  static Decision of(Limit limit, boolean allowed, double tokens) {
    double rate = limit.tokensPerMilli();
    Duration retryAfter = allowed ? Duration.ZERO : millis((1 - tokens) / rate);
    Duration resetAfter = millis((limit.capacity() - tokens) / rate);
    return new Decision(
        allowed, true, limit.capacity(), (long) Math.floor(tokens), retryAfter, resetAfter);
  }

  private static Duration millis(double value) {
    return Duration.ofMillis((long) Math.ceil(Math.max(0, value)));
  }
}
