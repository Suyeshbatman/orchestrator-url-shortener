package com.acme.orchestrator.core.event;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An event to append; the store assigns seq, timestamp and the hash chain. */
public record EventDraft(
    String runId,
    EventType type,
    Optional<String> stageId,
    Actor actor,
    Map<String, Object> payload) {

  public EventDraft {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(type, "type");
    stageId = Objects.requireNonNull(stageId, "stageId");
    Objects.requireNonNull(actor, "actor");
    payload = CanonicalJson.normalizeMap(Objects.requireNonNull(payload, "payload"));
  }

  public static EventDraft run(String runId, EventType type, Actor actor, Map<String, ?> payload) {
    return new EventDraft(runId, type, Optional.empty(), actor, Map.copyOf(payload));
  }

  public static EventDraft stage(
      String runId, EventType type, String stageId, Actor actor, Map<String, ?> payload) {
    return new EventDraft(runId, type, Optional.of(stageId), actor, Map.copyOf(payload));
  }
}
