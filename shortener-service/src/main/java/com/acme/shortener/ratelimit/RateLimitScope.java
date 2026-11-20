package com.acme.shortener.ratelimit;

/** What is being limited, and so which limit, key and failure policy apply. */
public enum RateLimitScope {
  /** Link creation, keyed by API key name. Falls back to a local bucket if Redis is down. */
  CREATE("create"),
  /** Redirects, keyed by visitor fingerprint. Fails open if Redis is down. */
  REDIRECT("redirect");

  private final String key;

  RateLimitScope(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
