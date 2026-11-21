package com.acme.orchestrator.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.Hashing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditChainTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final InMemoryEventStore store = new InMemoryEventStore(clock);
  private final AuditVerifier verifier = new AuditVerifier(store);

  @BeforeEach
  void fiveEvents() {
    store.append(EventDraft.run("run-1", EventType.RUN_CREATED, Actor.SYSTEM, Map.of()));
    for (int i = 0; i < 4; i++) {
      clock.advance(Duration.ofSeconds(1));
      store.append(
          EventDraft.stage(
              "run-1",
              EventType.GATE_EVALUATED,
              "validation",
              Actor.agent("ValidationAgent"),
              Map.of("gate", "coverage.min", "value", 0.81 + i / 100.0, "passed", true)));
    }
  }

  @Test
  void appendChainsEachEventToThePreviousOne() {
    List<Event> log = store.events("run-1");

    assertThat(log).extracting(Event::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(log.getFirst().prevHash()).isEqualTo(Event.GENESIS);
    for (int i = 1; i < log.size(); i++) {
      assertThat(log.get(i).prevHash()).isEqualTo(log.get(i - 1).hash());
    }
    Event first = log.getFirst();
    assertThat(first.hash()).isEqualTo(Hashing.sha256Hex(Event.GENESIS + first.canonicalBody()));
    assertThat(store.head("run-1")).contains(log.getLast());
  }

  @Test
  void canonicalBodyIsStableAndExcludesTheHash() {
    Event e = store.events("run-1").get(1);

    assertThat(e.canonicalBody())
        .isEqualTo(
            "{\"actor\":\"agent:ValidationAgent\","
                + "\"payload\":{\"gate\":\"coverage.min\",\"passed\":true,\"value\":0.81},"
                + "\"prevHash\":\""
                + e.prevHash()
                + "\",\"runId\":\"run-1\",\"seq\":2,\"stageId\":\"validation\","
                + "\"timestamp\":\"2026-01-01T00:00:01Z\",\"type\":\"GATE_EVALUATED\"}")
        .doesNotContain(e.hash());
  }

  @Test
  void anIntactChainVerifies() {
    AuditReport report = verifier.verify("run-1");

    assertThat(report.valid()).isTrue();
    assertThat(report.eventCount()).isEqualTo(5);
    assertThat(report.firstBrokenSeq()).isEmpty();
    assertThat(report.headHash()).isEqualTo(store.head("run-1").orElseThrow().hash());
  }

  @Test
  void tamperingAPayloadIsDetectedAtThatEvent() {
    List<Event> log = new ArrayList<>(store.events("run-1"));
    Event original = log.get(2);
    log.set(
        2,
        copyWithPayload(original, Map.of("gate", "coverage.min", "value", 0.99, "passed", true)));

    AuditReport report = AuditVerifier.verify("run-1", log);

    assertThat(report.valid()).isFalse();
    assertThat(report.firstBrokenSeq()).contains(3L);
    assertThat(report.problem()).contains(AuditReport.Problem.HASH_MISMATCH);
  }

  @Test
  void rehashingATamperedEventBreaksTheNextLink() {
    List<Event> log = new ArrayList<>(store.events("run-1"));
    Event forged = copyWithPayload(log.get(2), Map.of("passed", false));
    log.set(2, forged.withHash(forged.computeHash())); // attacker fixes this event's own hash

    AuditReport report = AuditVerifier.verify("run-1", log);

    assertThat(report.firstBrokenSeq()).contains(4L);
    assertThat(report.problem()).contains(AuditReport.Problem.BROKEN_LINK);
  }

  @Test
  void deletingOrReorderingEventsIsDetected() {
    List<Event> deleted = new ArrayList<>(store.events("run-1"));
    deleted.remove(1);
    assertThat(AuditVerifier.verify("run-1", deleted).firstBrokenSeq()).contains(3L);
    assertThat(AuditVerifier.verify("run-1", deleted).problem())
        .contains(AuditReport.Problem.SEQUENCE_GAP);

    List<Event> swapped = new ArrayList<>(store.events("run-1"));
    Event two = swapped.get(1);
    swapped.set(1, swapped.get(2));
    swapped.set(2, two);
    assertThat(AuditVerifier.verify("run-1", swapped).firstBrokenSeq()).contains(3L);
  }

  @Test
  void eventsFromAnotherRunAreRejected() {
    List<Event> log = new ArrayList<>(store.events("run-1"));
    store.append(EventDraft.run("run-2", EventType.RUN_CREATED, Actor.SYSTEM, Map.of()));
    log.add(store.events("run-2").getFirst());

    AuditReport report = AuditVerifier.verify("run-1", log);

    assertThat(report.problem()).contains(AuditReport.Problem.WRONG_RUN);
    assertThat(report.firstBrokenSeq()).contains(1L); // the foreign event's own seq
  }

  @Test
  void truncatingTheTailIsNotDetectableWithoutAnAnchoredHead() {
    List<Event> log = store.events("run-1");
    List<Event> truncated = log.subList(0, 3);

    AuditReport report = AuditVerifier.verify("run-1", truncated);

    assertThat(report.valid()).isTrue(); // documented limitation L-6 ...
    assertThat(report.headHash()).isNotEqualTo(log.getLast().hash()); // ... caught by an anchor
  }

  @Test
  void runsHaveIndependentChains() {
    Event other =
        store.append(EventDraft.run("run-2", EventType.RUN_CREATED, Actor.SYSTEM, Map.of()));

    assertThat(other.seq()).isEqualTo(1);
    assertThat(other.prevHash()).isEqualTo(Event.GENESIS);
    assertThat(store.runIds()).containsExactly("run-1", "run-2");
    assertThat(verifier.verify("run-2").valid()).isTrue();
    assertThat(store.events("unknown")).isEmpty();
    assertThat(verifier.verify("unknown").valid()).isTrue();
  }

  /** Values as a database would give them back must hash exactly as the original. */
  @Test
  void anEventReloadedFromStorageHashesTheSame() {
    clock.advance(Duration.ofNanos(123_456_789)); // more precision than Postgres keeps
    Event stored =
        store.append(
            EventDraft.stage(
                "run-1",
                EventType.GATE_EVALUATED,
                "validation",
                Actor.agent("ValidationAgent"),
                Map.of("threshold", 0.80, "count", 3)));

    Event reloaded =
        new Event(
            stored.seq(),
            stored.runId(),
            stored.type(),
            stored.stageId(),
            Actor.parse(stored.actor().toString()),
            Map.of("threshold", new BigDecimal("0.800"), "count", 3L), // JSONB-style values
            stored.timestamp().plusNanos(0),
            stored.prevHash(),
            stored.hash());

    assertThat(stored.timestamp().getNano() % 1000).isZero(); // microsecond precision
    assertThat(reloaded.hashMatches()).isTrue();
    assertThat(reloaded).isEqualTo(stored);
  }

  @Test
  void actorsRoundTripAndRejectGarbage() {
    assertThat(Actor.parse("agent:DesignAgent")).isEqualTo(Actor.agent("DesignAgent"));
    assertThat(Actor.parse("human:alice")).hasToString("human:alice");
    assertThat(Actor.parse("system")).isSameAs(Actor.SYSTEM);
    assertThatThrownBy(() -> Actor.parse("robot:x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Actor.human("has space")).isInstanceOf(IllegalArgumentException.class);
  }

  private static Event copyWithPayload(Event e, Map<String, Object> payload) {
    return new Event(
        e.seq(),
        e.runId(),
        e.type(),
        e.stageId(),
        e.actor(),
        payload,
        e.timestamp(),
        e.prevHash(),
        e.hash());
  }
}
