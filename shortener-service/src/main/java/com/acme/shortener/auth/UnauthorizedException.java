package com.acme.shortener.auth;

/** Missing or invalid API key. Maps to 401. */
public class UnauthorizedException extends RuntimeException {

  public UnauthorizedException() {
    super("a valid X-API-Key header is required");
  }
}
