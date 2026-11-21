package com.acme.orchestrator.app.api;

import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.model.Hashing;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API keys and the roles they carry. Only SHA-256 hashes are configured; a presented key is hashed
 * and compared in constant time against every entry.
 */
public final class ApiKeys {

  public enum Role {
    /** Starts runs, answers questions, amends requirements, halts, resumes, rolls back. */
    OPERATOR,
    /** Decides approvals. */
    APPROVER
  }

  /** Who is calling. Approvals and commands are recorded as {@code human:<name>}. */
  public record Principal(String name, Set<Role> roles) {
    public boolean has(Role role) {
      return roles.contains(role);
    }

    public Actor actor() {
      return Actor.human(name);
    }
  }

  private static final Pattern ENTRY = Pattern.compile("([A-Za-z0-9._@-]{1,64}):([0-9a-f]{64})");

  private record Entry(String name, byte[] hash) {}

  private final Map<String, Entry> byHash = new LinkedHashMap<>();
  private final Map<String, Set<Role>> roles = new LinkedHashMap<>();

  /**
   * @throws IllegalArgumentException for a malformed entry (better to fail at startup than to lock
   *     everyone out silently)
   */
  public ApiKeys(String operatorKeys, String approverKeys) {
    add(operatorKeys, Role.OPERATOR);
    add(approverKeys, Role.APPROVER);
  }

  private void add(String config, Role role) {
    if (config == null || config.isBlank()) {
      return;
    }
    for (String raw : config.split(",")) {
      String entry = raw.strip();
      int colon = entry.lastIndexOf(':');
      String name = colon < 0 ? entry : entry.substring(0, colon);
      String hash = colon < 0 ? "" : entry.substring(colon + 1).toLowerCase(Locale.ROOT);
      if (!ENTRY.matcher(name + ":" + hash).matches()) {
        throw new IllegalArgumentException(
            "API keys must be name:sha256hex (64 hex characters), got '" + name + ":…'");
      }
      Entry existing = byHash.get(hash);
      if (existing != null && !existing.name().equals(name)) {
        throw new IllegalArgumentException(
            "one key is configured for two names: " + existing.name());
      }
      byHash.put(hash, new Entry(name, hash.getBytes(StandardCharsets.US_ASCII)));
      roles.computeIfAbsent(name, k -> EnumSet.noneOf(Role.class)).add(role);
    }
  }

  public Optional<Principal> authenticate(String presentedKey) {
    if (presentedKey == null || presentedKey.isBlank()) {
      return Optional.empty();
    }
    byte[] presented = Hashing.sha256Hex(presentedKey).getBytes(StandardCharsets.US_ASCII);
    Entry match = null;
    for (Entry e : byHash.values()) { // no early exit: constant time in the number of keys
      if (MessageDigest.isEqual(e.hash(), presented)) {
        match = e;
      }
    }
    return Optional.ofNullable(match)
        .map(e -> new Principal(e.name(), Set.copyOf(roles.get(e.name()))));
  }

  public boolean isEmpty() {
    return byHash.isEmpty();
  }
}
