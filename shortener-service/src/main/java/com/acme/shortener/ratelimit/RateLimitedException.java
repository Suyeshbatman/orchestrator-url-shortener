package com.acme.shortener.ratelimit;

/** The bucket is empty: answered with 429 and the decision's headers. */
public final class RateLimitedException extends RuntimeException {

  private final Decision decision;

  public RateLimitedException(Decision decision) {
    super(
        "rate limit exceeded; retry after "
            + RateLimitHeaders.retryAfterSeconds(decision)
            + " second(s)");
    this.decision = decision;
  }

  public Decision decision() {
    return decision;
  }
}
