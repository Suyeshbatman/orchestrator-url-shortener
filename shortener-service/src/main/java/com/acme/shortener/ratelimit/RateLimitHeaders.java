package com.acme.shortener.ratelimit;

import java.time.Duration;
import org.springframework.http.HttpHeaders;

/**
 * {@code RateLimit-Limit/Remaining/Reset} (IETF draft-ietf-httpapi-ratelimit-headers), plus {@code
 * Retry-After} on a 429. Times are whole seconds, rounded up.
 */
public final class RateLimitHeaders {

  public static final String LIMIT = "RateLimit-Limit";
  public static final String REMAINING = "RateLimit-Remaining";
  public static final String RESET = "RateLimit-Reset";

  private RateLimitHeaders() {}

  public static HttpHeaders of(Decision decision) {
    HttpHeaders headers = new HttpHeaders();
    if (!decision.metered()) {
      return headers;
    }
    headers.set(LIMIT, Long.toString(decision.limit()));
    headers.set(REMAINING, Long.toString(decision.remaining()));
    headers.set(RESET, Long.toString(seconds(decision.resetAfter())));
    if (!decision.allowed()) {
      headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(decision)));
    }
    return headers;
  }

  static long retryAfterSeconds(Decision decision) {
    return Math.max(1, seconds(decision.retryAfter()));
  }

  private static long seconds(Duration d) {
    return (d.toMillis() + 999) / 1000;
  }
}
