package com.acme.orchestrator.core.event;

import com.acme.orchestrator.core.model.Hashing;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry in a run's hash-chained log: {@code hash = SHA-256(prevHash + canonical(event without
 * hash))}. Changing or removing any event breaks every later hash (DESIGN_RATIONALE §4.6).
 *
 * <p>Constructing an event does not check its hash, so a tampered log can still be loaded and
 * reported on; {@link AuditVerifier} does the checking. The payload is normalized and the timestamp
 * truncated to microseconds (Postgres precision), so a stored-and-reloaded event hashes the same.
 *
 * @param seq position in the run's log, from 1
 * @param prevHash hash of the previous event, or {@link #GENESIS} for the first
 */
public record Event(
    long seq,
    String runId,
    EventType type,
    Optional<String> stageId,
    Actor actor,
    Map<String, Object> payload,
    Instant timestamp,
    String prevHash,
    String hash) {

  /** prevHash of a run's first event. */
  public static final String GENESIS = "0".repeat(64);

  public Event {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(type, "type");
    stageId = Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(actor, "actor");
    payload = CanonicalJson.normalizeMap(Objects.requireNonNull(payload, "payload"));
    timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MICROS);
    Objects.requireNonNull(prevHash, "prevHash");
    Objects.requireNonNull(hash, "hash");
    if (seq < 1) {
      throw new IllegalArgumentException("seq starts at 1");
    }
  }

  /** A new event chained onto {@code prevHash}, with its hash computed. */
  public static Event chain(
      long seq,
      String runId,
      EventType type,
      Optional<String> stageId,
      Actor actor,
      Map<String, ?> payload,
      Instant timestamp,
      String prevHash) {
    Event unhashed =
        new Event(seq, runId, type, stageId, actor, Map.copyOf(payload), timestamp, prevHash, "");
    return unhashed.withHash(unhashed.computeHash());
  }

  /** The canonical JSON that is hashed: every field except {@code hash}. */
  public String canonicalBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("seq", seq);
    body.put("runId", runId);
    body.put("type", type.name());
    stageId.ifPresent(s -> body.put("stageId", s));
    body.put("actor", actor.toString());
    body.put("payload", payload);
    body.put("timestamp", timestamp.toString());
    body.put("prevHash", prevHash);
    return CanonicalJson.write(body);
  }

  public String computeHash() {
    return Hashing.sha256Hex(prevHash + canonicalBody());
  }

  public boolean hashMatches() {
    return hash.equals(computeHash());
  }

  /** Payload value as text, if present. */
  public Optional<String> payloadString(String key) {
    Object v = payload.get(key);
    return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
  }

  Event withHash(String newHash) {
    return new Event(seq, runId, type, stageId, actor, payload, timestamp, prevHash, newHash);
  }
}
