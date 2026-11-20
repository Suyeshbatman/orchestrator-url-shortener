package com.acme.shortener.url;

/**
 * A destination URL that failed validation. {@link Reason} gives a stable machine-readable cause.
 */
public class InvalidUrlException extends RuntimeException {

  public enum Reason {
    TOO_LONG,
    MALFORMED,
    SCHEME_NOT_ALLOWED,
    MISSING_HOST,
    USERINFO_NOT_ALLOWED,
    NON_CANONICAL_IP,
    PRIVATE_ADDRESS,
    UNRESOLVABLE_HOST,
    SELF_REFERENCE
  }

  private final Reason reason;

  public InvalidUrlException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
