package com.acme.orchestrator.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 as lowercase hex, the hash format used for artifacts, prompts and the audit chain. */
public final class Hashing {

  private Hashing() {}

  public static String sha256Hex(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }
}
