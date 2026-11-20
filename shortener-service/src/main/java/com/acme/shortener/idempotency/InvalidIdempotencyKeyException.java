package com.acme.shortener.idempotency;

/** Idempotency-Key header that is empty, too long or has non-printable characters. Maps to 400. */
public class InvalidIdempotencyKeyException extends RuntimeException {

  public InvalidIdempotencyKeyException() {
    super("Idempotency-Key must be 1-128 printable ASCII characters");
  }
}
