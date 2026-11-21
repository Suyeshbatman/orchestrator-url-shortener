package com.acme.orchestrator.app.metrics;

import com.acme.orchestrator.core.event.Event;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Live Micrometer metrics, fed every committed event by the event store, so no code path can forget
 * to record. Counts are tagged by stage (a workflow has a handful) or outcome, never by run.
 *
 * <p>Timers that span events (approval wait, run duration) remember their start in memory; after a
 * restart the first measurement of a run already under way is skipped. {@code /api/metrics/summary}
 * computes the exact figures from the log instead.
 */
public final class MetricsRecorder implements Consumer<Event> {

  private final MeterRegistry registry;
  private final Map<String, Instant> runStarted = new ConcurrentHashMap<>();
  private final Map<String, Instant> approvalRequested = new ConcurrentHashMap<>();

  public MetricsRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void accept(Event e) {
    String stage = e.stageId().orElse("none");
    switch (e.type()) {
      case RUN_CREATED -> runStarted.put(e.runId(), e.timestamp());
      case STAGE_SUCCEEDED -> execution(stage, "succeeded");
      case STAGE_FAILED -> execution(stage, "failed");
      case STAGE_SKIPPED -> execution(stage, "skipped");
      case STAGE_RETRIED -> counter("orchestrator.stage.retries", "stage", stage).increment();
      case FALLBACK_USED -> counter("orchestrator.fallbacks", "stage", stage).increment();
      case ROLLED_BACK -> counter("orchestrator.rollbacks", "stage", stage).increment();
      case REPLAN_TRIGGERED ->
          counter("orchestrator.replans", "trigger", e.payloadString("trigger").orElse("unknown"))
              .increment();
      case APPROVAL_REQUESTED -> approvalRequested.put(key(e), e.timestamp());
      case APPROVAL_GRANTED, APPROVAL_REJECTED -> {
        Instant requested = approvalRequested.remove(key(e));
        if (requested != null) {
          Timer.builder("orchestrator.approvals.wait")
              .tag("stage", stage)
              .tag(
                  "decision",
                  e.type().name().substring("APPROVAL_".length()).toLowerCase(Locale.ROOT))
              .publishPercentileHistogram()
              .register(registry)
              .record(Duration.between(requested, e.timestamp()));
        }
      }
      case RUN_COMPLETED, RUN_FAILED -> {
        Instant started = runStarted.remove(e.runId());
        if (started != null) {
          Timer.builder("orchestrator.run.duration")
              .tag(
                  "outcome",
                  e.type() == com.acme.orchestrator.core.event.EventType.RUN_COMPLETED
                      ? "completed"
                      : "failed")
              .publishPercentileHistogram()
              .register(registry)
              .record(Duration.between(started, e.timestamp()));
        }
        approvalRequested.keySet().removeIf(k -> k.startsWith(e.runId() + "\n"));
      }
      case USAGE_RECORDED -> {
        counter("orchestrator.llm.calls", "stage", stage)
            .increment(number(e.payload().get("llmCalls")));
        counter("orchestrator.llm.tokens", "stage", stage)
            .increment(number(e.payload().get("tokens")));
      }
      case POLICY_EVALUATED -> {
        if ("DENY".equals(e.payloadString("effect").orElse(""))) {
          counter("orchestrator.policy.denials", "stage", stage).increment();
        }
      }
      case BUDGET_EXCEEDED ->
          counter("orchestrator.budget.exceeded", "budget", e.payloadString("budget").orElse("llm"))
              .increment();
      default -> {}
    }
  }

  private void execution(String stage, String outcome) {
    counter("orchestrator.stage.executions", "stage", stage, "outcome", outcome).increment();
  }

  private Counter counter(String name, String... tags) {
    return Counter.builder(name).tags(tags).register(registry);
  }

  private static String key(Event e) {
    return e.runId() + "\n" + e.stageId().orElse("");
  }

  private static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }
}
