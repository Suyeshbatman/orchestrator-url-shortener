package com.acme.shortener.idempotency;

/** Same Idempotency-Key with a different request body. Maps to 422. */
public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("this Idempotency-Key was already used with a different request body");
  }
}
