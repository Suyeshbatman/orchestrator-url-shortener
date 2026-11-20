package com.acme.shortener.api;

import java.net.URI;

/** Stable Problem Details {@code type} URIs. Changing one is a breaking API change. */
public final class ProblemTypes {

  public record Type(URI uri, String title) {}

  public static final Type VALIDATION_FAILED = type("validation-failed", "Validation failed");
  public static final Type MALFORMED_REQUEST = type("malformed-request", "Malformed request");
  public static final Type INVALID_URL = type("invalid-url", "Invalid URL");
  public static final Type INVALID_ALIAS = type("invalid-alias", "Invalid alias");
  public static final Type INVALID_IDEMPOTENCY_KEY =
      type("invalid-idempotency-key", "Invalid Idempotency-Key");
  public static final Type UNAUTHORIZED = type("unauthorized", "Unauthorized");
  public static final Type LINK_NOT_FOUND = type("link-not-found", "Link not found");
  public static final Type ALIAS_TAKEN = type("alias-taken", "Alias already taken");
  public static final Type IDEMPOTENCY_KEY_REUSED =
      type("idempotency-key-reused", "Idempotency-Key reused with a different request");
  public static final Type RATE_LIMITED = type("rate-limited", "Too many requests");
  public static final Type INTERNAL_ERROR = type("internal-error", "Internal error");

  private ProblemTypes() {}

  private static Type type(String slug, String title) {
    return new Type(URI.create("/problems/" + slug), title);
  }
}
