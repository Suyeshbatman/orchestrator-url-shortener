package com.acme.orchestrator.core.event;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Budgets;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageEvent;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebuilds a run's state purely from its events (event sourcing). Used for crash recovery: replay
 * the log, continue from {@link RunState#lastSeq()}. Deterministic: stage timestamps come from the
 * events, never from a clock.
 *
 * <p>Stage events map one-to-one onto {@link StageEvent}s (STAGE_READY → DEPENDENCIES_MET,
 * STAGE_STARTED → START, …). Rules for events that imply more:
 *
 * <ul>
 *   <li>STAGE_READY on an INVALIDATED stage first resets it (a new loop), then marks it ready.
 *   <li>RUN_HALTED halts every stage that is in progress (PENDING, READY, RUNNING,
 *       WAITING_APPROVAL, RETRYING); RUN_RESUMED resets every HALTED stage to PENDING.
 *   <li>ROLLED_BACK with a stage rolls that stage back; without one, the whole run.
 *   <li>Run status: RUN_* events set it; otherwise a non-final run is AWAITING_HUMAN while some
 *       stage waits for approval and nothing is runnable, and RUNNING after any stage activity.
 * </ul>
 *
 * Other events (AGENT_INVOKED, ARTIFACT_PRODUCED, GATE_EVALUATED, …) are audit-only.
 *
 * <p>A log that breaks these rules (an illegal transition, events before RUN_CREATED) throws: it is
 * corrupt, and recovering from it silently would be worse.
 */
public final class RunStateProjector {

  public static final String WORKFLOW_ID = "workflowId";
  public static final String MODE = "mode";
  public static final String AUTONOMY_LEVEL = "autonomyLevel";
  public static final String MAX_WALL_CLOCK = "maxWallClock";
  public static final String MAX_LLM_CALLS = "maxLlmCalls";
  public static final String MAX_TOKENS = "maxTokens";

  /** SHA-256 of the policies.yml / autonomy.yml the run started under (change control). */
  public static final String POLICIES_HASH = "policiesHash";

  public static final String AUTONOMY_HASH = "autonomyHash";

  /** Set on stage events of an expanded task. */
  public static final String TASK_ID = "taskId";

  private RunStateProjector() {}

  /** The RUN_CREATED payload for {@code run}; the projector reads it back. */
  public static Map<String, Object> runCreatedPayload(Run run) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(WORKFLOW_ID, run.workflowId());
    p.put(MODE, run.mode());
    p.put(AUTONOMY_LEVEL, run.autonomyLevel());
    p.put(MAX_WALL_CLOCK, run.budgets().maxWallClock());
    p.put(MAX_LLM_CALLS, run.budgets().maxLlmCalls());
    p.put(MAX_TOKENS, run.budgets().maxTokens());
    return p;
  }

  public static RunState project(List<Event> events) {
    if (events.isEmpty() || events.getFirst().type() != EventType.RUN_CREATED) {
      throw new IllegalStateException("a run's log must start with RUN_CREATED");
    }
    Event created = events.getFirst();
    Run run = runFrom(created);
    Map<String, StageInstance> stages = new LinkedHashMap<>();

    for (Event e : events.subList(1, events.size())) {
      if (!e.runId().equals(run.id())) {
        throw new IllegalStateException("event " + e.seq() + " belongs to run " + e.runId());
      }
      Clock at = Clock.fixed(e.timestamp(), ZoneOffset.UTC);
      run =
          switch (e.type()) {
            case RUN_CREATED ->
                throw new IllegalStateException("second RUN_CREATED at seq " + e.seq());
            case RUN_HALTED -> {
              stages.replaceAll(
                  (k, s) -> inProgress(s.status()) ? s.apply(StageEvent.HALT, at) : s);
              yield run.withStatus(RunStatus.HALTED);
            }
            case RUN_RESUMED -> {
              stages.replaceAll(
                  (k, s) -> s.status() == StageStatus.HALTED ? s.apply(StageEvent.RESET, at) : s);
              yield run.withStatus(RunStatus.RUNNING);
            }
            case RUN_COMPLETED -> run.withStatus(RunStatus.COMPLETED);
            case RUN_FAILED -> run.withStatus(RunStatus.FAILED);
            case ROLLED_BACK -> {
              if (e.stageId().isEmpty()) {
                yield run.withStatus(RunStatus.ROLLED_BACK);
              }
              applyStage(stages, run, e, StageEvent.ROLL_BACK, at);
              yield afterStageActivity(run, stages);
            }
            default -> {
              Optional<StageEvent> stageEvent = stageEvent(e.type());
              if (stageEvent.isEmpty()) {
                yield run; // audit-only
              }
              applyStage(stages, run, e, stageEvent.get(), at);
              yield afterStageActivity(run, stages);
            }
          };
    }
    Event last = events.getLast();
    return new RunState(run, stages, last.seq(), last.hash());
  }

  private static void applyStage(
      Map<String, StageInstance> stages, Run run, Event e, StageEvent event, Clock at) {
    String stageId =
        e.stageId()
            .orElseThrow(
                () -> new IllegalStateException(e.type() + " at seq " + e.seq() + " has no stage"));
    Optional<String> taskId = e.payloadString(TASK_ID);
    StageInstance fresh =
        taskId
            .map(t -> StageInstance.pendingTask(run.id(), stageId, t))
            .orElseGet(() -> StageInstance.pending(run.id(), stageId));
    StageInstance current = stages.getOrDefault(fresh.key(), fresh);
    if (event == StageEvent.DEPENDENCIES_MET && current.status() == StageStatus.INVALIDATED) {
      current = current.apply(StageEvent.RESET, at);
    }
    stages.put(current.key(), current.apply(event, at));
  }

  private static Run afterStageActivity(Run run, Map<String, StageInstance> stages) {
    if (run.status().isTerminal() || run.status() == RunStatus.HALTED) {
      return run;
    }
    boolean waiting = false;
    boolean runnable = false;
    for (StageInstance s : stages.values()) {
      switch (s.status()) {
        case WAITING_APPROVAL -> waiting = true;
        case READY, RUNNING, RETRYING -> runnable = true;
        default -> {}
      }
    }
    return run.withStatus(waiting && !runnable ? RunStatus.AWAITING_HUMAN : RunStatus.RUNNING);
  }

  private static Optional<StageEvent> stageEvent(EventType type) {
    return Optional.ofNullable(
        switch (type) {
          case STAGE_READY -> StageEvent.DEPENDENCIES_MET;
          case STAGE_STARTED -> StageEvent.START;
          case APPROVAL_REQUESTED -> StageEvent.AWAIT_APPROVAL;
          case APPROVAL_GRANTED -> StageEvent.APPROVE;
          case APPROVAL_REJECTED -> StageEvent.REJECT;
          case STAGE_SUCCEEDED -> StageEvent.SUCCEED;
          case STAGE_FAILED -> StageEvent.FAIL;
          case STAGE_RETRIED -> StageEvent.SCHEDULE_RETRY;
          case STAGE_SKIPPED -> StageEvent.SKIP;
          case STAGE_INVALIDATED -> StageEvent.INVALIDATE;
          default -> null;
        });
  }

  private static boolean inProgress(StageStatus s) {
    return switch (s) {
      case PENDING, READY, RUNNING, WAITING_APPROVAL, RETRYING -> true;
      default -> false;
    };
  }

  private static Run runFrom(Event created) {
    Map<String, Object> p = created.payload();
    try {
      Budgets budgets =
          new Budgets(
              Duration.parse((String) p.get(MAX_WALL_CLOCK)),
              Math.toIntExact((Long) p.get(MAX_LLM_CALLS)),
              (Long) p.get(MAX_TOKENS));
      return new Run(
          created.runId(),
          (String) p.get(WORKFLOW_ID),
          RunMode.valueOf((String) p.get(MODE)),
          AutonomyLevel.valueOf((String) p.get(AUTONOMY_LEVEL)),
          RunStatus.CREATED,
          budgets);
    } catch (RuntimeException e) {
      throw new IllegalStateException("RUN_CREATED payload is incomplete: " + p, e);
    }
  }
}
