package com.acme.orchestrator.app.metrics;

import static com.acme.orchestrator.core.event.EventType.APPROVAL_GRANTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REJECTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REQUESTED;
import static com.acme.orchestrator.core.event.EventType.FALLBACK_USED;
import static com.acme.orchestrator.core.event.EventType.STAGE_FAILED;
import static com.acme.orchestrator.core.event.EventType.STAGE_INVALIDATED;
import static com.acme.orchestrator.core.event.EventType.STAGE_RETRIED;
import static com.acme.orchestrator.core.event.EventType.STAGE_STARTED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SUCCEEDED;

import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reliability figures computed from event logs (the source of truth, so they survive restarts and
 * can be recomputed for any set of runs).
 *
 * <ul>
 *   <li>run success rate: completed / (completed + failed) over finished runs;
 *   <li>stage success rate: successes / (successes + STAGE_FAILED). A success is STAGE_SUCCEEDED,
 *       or the approval of a stage that has run (a post-run approval completes the stage without a
 *       STAGE_SUCCEEDED event); an approval given before a stage runs does not complete it;
 *   <li>retry rate: retries per started attempt;
 *   <li>rollback frequency: ROLLED_BACK events per run;
 *   <li>MTTR: mean of stage incidents, each from a stage's first failure signal (retry, fallback,
 *       failure, or a validation loop-back) to that stage's next success; incidents not yet
 *       resolved are counted apart. A human rejection is governance, not an incident;
 *   <li>end-to-end latency of completed runs (p50, p95), also with the time spent waiting for
 *       approvals taken out, and the mean approval wait itself, so human time is not hidden in
 *       agent latency.
 * </ul>
 *
 * Rates and durations are {@code null} when there is nothing to measure yet.
 */
public record ReliabilitySummary(
    Runs runs,
    Double runSuccessRate,
    Stages stages,
    Double stageSuccessRate,
    Double retryRate,
    long rollbacks,
    Double rollbacksPerRun,
    Mttr mttr,
    Latency endToEnd,
    Latency endToEndExcludingApprovalWait,
    ApprovalWait approvalWait) {

  public record Runs(long total, long completed, long failed, long halted, long inProgress) {}

  public record Stages(long succeeded, long failed, long attempts, long retries, long fallbacks) {}

  public record Mttr(Double meanSeconds, long resolvedIncidents, long openIncidents) {}

  public record Latency(Double p50Seconds, Double p95Seconds, long runs) {}

  public record ApprovalWait(Double meanSeconds, Double maxSeconds, long decided) {}

  public static ReliabilitySummary compute(Collection<List<Event>> logs) {
    long completed = 0;
    long failed = 0;
    long halted = 0;
    long inProgress = 0;
    long stageSucceeded = 0;
    long stageFailed = 0;
    long attempts = 0;
    long retries = 0;
    long fallbacks = 0;
    long rollbacks = 0;
    List<Duration> incidents = new ArrayList<>();
    long openIncidents = 0;
    List<Duration> e2e = new ArrayList<>();
    List<Duration> e2eWithoutWait = new ArrayList<>();
    List<Duration> waits = new ArrayList<>();

    for (List<Event> log : logs) {
      if (log.isEmpty()) {
        continue;
      }
      Instant created = log.getFirst().timestamp();
      Map<String, Instant> incidentStart = new HashMap<>();
      Set<String> running = new HashSet<>(); // started, and not yet finished one way or another
      Set<String> completedByApproval = new HashSet<>();
      Map<String, Instant> waitingSince = new HashMap<>();
      Duration runWait = Duration.ZERO;
      EventType end = null;
      Instant endedAt = null;
      boolean halt = false;
      for (Event e : log) {
        String stage = e.stageId().orElse("");
        switch (e.type()) {
          case STAGE_STARTED -> attempts++;
          case STAGE_RETRIED -> retries++;
          case FALLBACK_USED -> fallbacks++;
          case ROLLED_BACK -> rollbacks++;
          case STAGE_FAILED -> stageFailed++;
          case RUN_HALTED -> halt = true;
          case RUN_RESUMED -> halt = false;
          case RUN_COMPLETED, RUN_FAILED -> {
            end = e.type();
            endedAt = e.timestamp();
          }
          default -> {}
        }
        boolean succeeded = false;
        switch (e.type()) {
          case STAGE_STARTED -> {
            running.add(stage);
            completedByApproval.remove(stage);
          }
          // a STAGE_SUCCEEDED right after the approval that completed the stage is the same success
          case STAGE_SUCCEEDED -> succeeded = !completedByApproval.remove(stage);
          case APPROVAL_GRANTED -> {
            succeeded = running.contains(stage);
            if (succeeded) {
              completedByApproval.add(stage);
            }
          }
          default -> {}
        }
        if (succeeded) {
          stageSucceeded++;
        }
        if (succeeded
            || e.type() == STAGE_FAILED
            || e.type() == STAGE_RETRIED
            || e.type() == FALLBACK_USED
            || e.type() == STAGE_INVALIDATED
            || e.type() == APPROVAL_REJECTED) {
          running.remove(stage);
        }
        boolean loopBack = e.type() == STAGE_INVALIDATED && e.payload().containsKey("loopTo");
        if (e.type() == STAGE_RETRIED
            || e.type() == FALLBACK_USED
            || e.type() == STAGE_FAILED
            || loopBack) {
          incidentStart.putIfAbsent(stage, e.timestamp());
        } else if (succeeded && incidentStart.containsKey(stage)) {
          incidents.add(Duration.between(incidentStart.remove(stage), e.timestamp()));
        }
        if (e.type() == APPROVAL_REQUESTED) {
          waitingSince.put(stage, e.timestamp());
        } else if ((e.type() == APPROVAL_GRANTED || e.type() == APPROVAL_REJECTED)
            && waitingSince.containsKey(stage)) {
          Duration wait = Duration.between(waitingSince.remove(stage), e.timestamp());
          waits.add(wait);
          runWait = runWait.plus(wait);
        }
      }
      openIncidents += incidentStart.size();
      if (end == EventType.RUN_COMPLETED) {
        completed++;
        Duration total = Duration.between(created, endedAt);
        e2e.add(total);
        e2eWithoutWait.add(
            total.minus(runWait).isNegative() ? Duration.ZERO : total.minus(runWait));
      } else if (end == EventType.RUN_FAILED) {
        failed++;
      } else if (halt) {
        halted++;
      } else {
        inProgress++;
      }
    }

    long runs = completed + failed + halted + inProgress;
    return new ReliabilitySummary(
        new Runs(runs, completed, failed, halted, inProgress),
        ratio(completed, completed + failed),
        new Stages(stageSucceeded, stageFailed, attempts, retries, fallbacks),
        ratio(stageSucceeded, stageSucceeded + stageFailed),
        ratio(retries, attempts),
        rollbacks,
        ratio(rollbacks, runs),
        new Mttr(mean(incidents), incidents.size(), openIncidents),
        latency(e2e),
        latency(e2eWithoutWait),
        new ApprovalWait(
            mean(waits),
            waits.stream().map(ReliabilitySummary::seconds).max(Double::compare).orElse(null),
            waits.size()));
  }

  private static Double ratio(long part, long whole) {
    return whole == 0 ? null : round((double) part / whole);
  }

  private static Double mean(List<Duration> durations) {
    return durations.isEmpty()
        ? null
        : round(
            durations.stream().mapToDouble(ReliabilitySummary::seconds).average().orElseThrow());
  }

  private static Latency latency(List<Duration> durations) {
    List<Double> sorted = durations.stream().map(ReliabilitySummary::seconds).sorted().toList();
    return new Latency(percentile(sorted, 50), percentile(sorted, 95), sorted.size());
  }

  /** Nearest-rank percentile. */
  static Double percentile(List<Double> sorted, int p) {
    if (sorted.isEmpty()) {
      return null;
    }
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return round(sorted.get(Math.max(0, rank - 1)));
  }

  private static double seconds(Duration d) {
    return d.toNanos() / 1e9;
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }
}
