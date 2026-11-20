package com.acme.shortener.code;

/** A custom alias that already exists, compared case-insensitively. Maps to 409. */
public class AliasTakenException extends RuntimeException {

  public AliasTakenException(String alias) {
    super("alias '" + alias + "' is already taken");
  }
}
