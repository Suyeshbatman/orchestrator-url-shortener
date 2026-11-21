package com.acme.shortener.analytics;

public final class InvalidStatsRangeException extends RuntimeException {

  public InvalidStatsRangeException(String message) {
    super(message);
  }
}
