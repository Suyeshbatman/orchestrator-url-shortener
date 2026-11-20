package com.acme.shortener.code;

/** A custom alias that breaks a format rule (length, characters, reserved word). Maps to 400. */
public class InvalidAliasException extends RuntimeException {

  public InvalidAliasException(String message) {
    super(message);
  }
}
