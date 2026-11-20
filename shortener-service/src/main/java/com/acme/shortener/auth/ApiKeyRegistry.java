package com.acme.shortener.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * API keys stored as SHA-256 hashes ({@code name:sha256hex,...}), so leaked config doesn't leak
 * keys. Presented keys are hashed and compared in constant time against every entry.
 */
public final class ApiKeyRegistry {

  private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
  private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

  private record Entry(String name, byte[] hash) {}

  private final List<Entry> entries;

  private ApiKeyRegistry(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** Parses {@code name:sha256hex} pairs separated by commas. Blank config = no keys. */
  public static ApiKeyRegistry parse(String config) {
    List<Entry> entries = new ArrayList<>();
    if (config == null || config.isBlank()) {
      return new ApiKeyRegistry(entries);
    }
    for (String pair : config.split(",")) {
      String trimmed = pair.strip();
      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        throw new IllegalArgumentException("api key entry must be name:sha256hex");
      }
      String name = trimmed.substring(0, colon);
      String hash = trimmed.substring(colon + 1);
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("invalid api key name: " + name);
      }
      if (!SHA256_HEX.matcher(hash).matches()) {
        throw new IllegalArgumentException("api key '" + name + "' must be a 64-char sha256 hex");
      }
      entries.add(new Entry(name, HexFormat.of().parseHex(hash.toLowerCase())));
    }
    return new ApiKeyRegistry(entries);
  }

  /** Returns the key's name, or empty. Checks every entry so timing doesn't reveal a match. */
  public Optional<String> authenticate(String presentedKey) {
    if (presentedKey == null || presentedKey.isEmpty()) {
      return Optional.empty();
    }
    byte[] presented = sha256(presentedKey);
    String match = null;
    for (Entry entry : entries) {
      if (MessageDigest.isEqual(entry.hash(), presented) && match == null) {
        match = entry.name();
      }
    }
    return Optional.ofNullable(match);
  }

  public int size() {
    return entries.size();
  }

  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String sha256Hex(String value) {
    return HexFormat.of().formatHex(sha256(value));
  }
}
