package com.acme.orchestrator.app.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MetricsTest {

  private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

  /** A run's log, built event by event at given offsets (in seconds) from T0. */
  private static final class Log {
    final String run;
    final List<Event> events = new ArrayList<>();

    Log(String run) {
      this.run = run;
      add(0, EventType.RUN_CREATED, null, Map.of());
    }

    Log add(long second, EventType type, String stage, Map<String, ?> payload) {
      Event previous = events.isEmpty() ? null : events.getLast();
      events.add(
          Event.chain(
              events.size() + 1,
              run,
              type,
              Optional.ofNullable(stage),
              type.name().startsWith("APPROVAL_G") || type.name().startsWith("APPROVAL_REJ")
                  ? Actor.human("alice")
                  : Actor.SYSTEM,
              payload,
              T0.plusSeconds(second),
              previous == null ? Event.GENESIS : previous.hash()));
      return this;
    }

    Log add(long second, EventType type, String stage) {
      return add(second, type, stage, Map.of());
    }
  }

  /** a fails once and recovers after 30 s; b waits 100 s for approval; done at 200 s. */
  private static Log recovered() {
    return new Log("r1")
        .add(1, EventType.STAGE_STARTED, "a")
        .add(10, EventType.STAGE_RETRIED, "a")
        .add(20, EventType.STAGE_STARTED, "a")
        .add(40, EventType.STAGE_SUCCEEDED, "a")
        .add(50, EventType.STAGE_STARTED, "b")
        .add(60, EventType.APPROVAL_REQUESTED, "b")
        .add(160, EventType.APPROVAL_GRANTED, "b")
        .add(170, EventType.REPLAN_TRIGGERED, null, Map.of("trigger", "amendment"))
        .add(180, EventType.USAGE_RECORDED, "b", Map.of("llmCalls", 2, "tokens", 1500))
        .add(190, EventType.STAGE_SUCCEEDED, "b")
        .add(200, EventType.RUN_COMPLETED, null);
  }

  /** c fails for good after a fallback; the run fails at 50 s. */
  private static Log failed() {
    return new Log("r2")
        .add(1, EventType.STAGE_STARTED, "c")
        .add(5, EventType.FALLBACK_USED, "c")
        .add(6, EventType.STAGE_STARTED, "c")
        .add(9, EventType.POLICY_EVALUATED, "c", Map.of("effect", "DENY"))
        .add(10, EventType.STAGE_FAILED, "c")
        .add(50, EventType.RUN_FAILED, null);
  }

  @Test
  void theSummaryIsComputedFromTheLogs() {
    Log halted =
        new Log("r3").add(1, EventType.ROLLED_BACK, "a").add(2, EventType.RUN_HALTED, null);
    Log running = new Log("r4").add(1, EventType.STAGE_STARTED, "a");

    ReliabilitySummary s =
        ReliabilitySummary.compute(
            List.of(recovered().events, failed().events, halted.events, running.events, List.of()));

    assertThat(s.runs()).isEqualTo(new ReliabilitySummary.Runs(4, 1, 1, 1, 1));
    assertThat(s.runSuccessRate()).isEqualTo(0.5);
    assertThat(s.stages()).isEqualTo(new ReliabilitySummary.Stages(2, 1, 6, 1, 1));
    assertThat(s.stageSuccessRate()).isEqualTo(0.667);
    assertThat(s.retryRate()).isEqualTo(0.167); // 1 retry in 6 attempts
    assertThat(s.rollbacks()).isEqualTo(1);
    assertThat(s.rollbacksPerRun()).isEqualTo(0.25);
    // a: 10 s → 40 s = 30 s; c never recovered
    assertThat(s.mttr()).isEqualTo(new ReliabilitySummary.Mttr(30.0, 1, 1));
    assertThat(s.endToEnd()).isEqualTo(new ReliabilitySummary.Latency(200.0, 200.0, 1));
    // 100 s of the 200 s were spent waiting for a human
    assertThat(s.endToEndExcludingApprovalWait())
        .isEqualTo(new ReliabilitySummary.Latency(100.0, 100.0, 1));
    assertThat(s.approvalWait()).isEqualTo(new ReliabilitySummary.ApprovalWait(100.0, 100.0, 1));
  }

  @Test
  void anEmptyLogHasNothingToMeasure() {
    ReliabilitySummary s = ReliabilitySummary.compute(List.of());

    assertThat(s.runSuccessRate()).isNull();
    assertThat(s.mttr().meanSeconds()).isNull();
    assertThat(s.endToEnd().p95Seconds()).isNull();
    assertThat(s.approvalWait().meanSeconds()).isNull();
  }

  @Test
  void percentilesUseTheNearestRank() {
    List<Double> sorted = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);

    assertThat(ReliabilitySummary.percentile(sorted, 50)).isEqualTo(5.0);
    assertThat(ReliabilitySummary.percentile(sorted, 95)).isEqualTo(10.0);
    assertThat(ReliabilitySummary.percentile(List.of(42.0), 95)).isEqualTo(42.0);
  }

  @Test
  void theRecorderTurnsEventsIntoMicrometerMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MetricsRecorder recorder = new MetricsRecorder(registry);

    recovered().events.forEach(recorder);
    failed().events.forEach(recorder);

    assertThat(
            registry
                .get("orchestrator.stage.executions")
                .tags("stage", "a", "outcome", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("orchestrator.stage.executions")
                .tags("outcome", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("orchestrator.stage.retries").counter().count()).isEqualTo(1);
    assertThat(registry.get("orchestrator.fallbacks").counter().count()).isEqualTo(1);
    assertThat(registry.get("orchestrator.replans").tags("trigger", "amendment").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("orchestrator.llm.tokens").counter().count()).isEqualTo(1500);
    assertThat(registry.get("orchestrator.llm.calls").counter().count()).isEqualTo(2);
    assertThat(registry.get("orchestrator.policy.denials").counter().count()).isEqualTo(1);
    assertThat(
            registry
                .get("orchestrator.approvals.wait")
                .tags("decision", "granted")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(100);
    assertThat(
            registry
                .get("orchestrator.run.duration")
                .tags("outcome", "completed")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(200);
    assertThat(
            registry
                .get("orchestrator.run.duration")
                .tags("outcome", "failed")
                .timer()
                .totalTime(TimeUnit.SECONDS))
        .isEqualTo(50);
  }
}
