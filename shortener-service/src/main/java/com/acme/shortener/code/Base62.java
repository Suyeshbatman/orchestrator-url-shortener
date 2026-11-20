package com.acme.shortener.code;

/** Fixed-length Base62 over the alphabet {@code 0-9a-zA-Z}. Encode/decode are O(length). */
public final class Base62 {

  static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final int BASE = 62;
  private static final int MAX_LENGTH = 10;

  private Base62() {}

  /** 62^length: the size of the code space for codes of this length. */
  public static long spaceSize(int length) {
    if (length < 1 || length > MAX_LENGTH) {
      throw new IllegalArgumentException("length must be 1.." + MAX_LENGTH + ", was " + length);
    }
    long size = 1;
    for (int i = 0; i < length; i++) {
      size *= BASE;
    }
    return size;
  }

  /** Encodes {@code value} left-padded with '0' to exactly {@code length} characters. */
  public static String encode(long value, int length) {
    if (value < 0 || value >= spaceSize(length)) {
      throw new IllegalArgumentException("value " + value + " out of range for length " + length);
    }
    char[] out = new char[length];
    for (int i = length - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (value % BASE));
      value /= BASE;
    }
    return new String(out);
  }

  public static long decode(String code) {
    if (code == null || code.isEmpty() || code.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("code must be 1.." + MAX_LENGTH + " characters");
    }
    long value = 0;
    for (int i = 0; i < code.length(); i++) {
      int digit = digitOf(code.charAt(i));
      if (digit < 0) {
        throw new IllegalArgumentException("invalid Base62 character: " + code.charAt(i));
      }
      value = value * BASE + digit;
    }
    return value;
  }

  private static int digitOf(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'z') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'Z') {
      return c - 'A' + 36;
    }
    return -1;
  }
}
