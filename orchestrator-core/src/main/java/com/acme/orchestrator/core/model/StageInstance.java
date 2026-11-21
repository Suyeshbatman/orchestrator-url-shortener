package com.acme.orchestrator.core.model;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stage (or one expanded task of it) within a run. Status changes go through {@link
 * #apply(StageEvent, Clock)}, which uses {@link StageStateMachine}, so an instance can't reach a
 * status the table doesn't allow.
 *
 * @param taskId set for tasks produced by {@code expand}
 * @param attempt attempts started so far (0 before the first)
 * @param loopCount how many times a loop-back has re-run it
 */
public record StageInstance(
    String runId,
    String stageId,
    Optional<String> taskId,
    StageStatus status,
    int attempt,
    int loopCount,
    Optional<Instant> startedAt,
    Optional<Instant> endedAt) {

  public StageInstance {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(stageId, "stageId");
    taskId = Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(status, "status");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
    endedAt = Objects.requireNonNull(endedAt, "endedAt");
    if (attempt < 0 || loopCount < 0) {
      throw new IllegalArgumentException("attempt and loopCount must not be negative");
    }
  }

  public static StageInstance pending(String runId, String stageId) {
    return new StageInstance(
        runId,
        stageId,
        Optional.empty(),
        StageStatus.PENDING,
        0,
        0,
        Optional.empty(),
        Optional.empty());
  }

  public static StageInstance pendingTask(String runId, String stageId, String taskId) {
    return new StageInstance(
        runId,
        stageId,
        Optional.of(taskId),
        StageStatus.PENDING,
        0,
        0,
        Optional.empty(),
        Optional.empty());
  }

  /** {@code stageId} or {@code stageId/taskId}: unique within a run. */
  public String key() {
    return taskId.map(t -> stageId + "/" + t).orElse(stageId);
  }

  /**
   * The instance after {@code event}. START counts an attempt and stamps {@code startedAt} on the
   * first one; settled statuses stamp {@code endedAt}; RESET starts a new loop with fresh counters.
   *
   * @throws IllegalTransitionException if the event isn't allowed in the current status
   */
  public StageInstance apply(StageEvent event, Clock clock) {
    StageStatus next = StageStateMachine.transition(status, event);
    Instant now = clock.instant();
    return switch (event) {
      case START ->
          new StageInstance(
              runId,
              stageId,
              taskId,
              next,
              attempt + 1,
              loopCount,
              startedAt.isPresent() ? startedAt : Optional.of(now),
              Optional.empty());
      case RESET ->
          new StageInstance(
              runId,
              stageId,
              taskId,
              next,
              0,
              status == StageStatus.INVALIDATED ? loopCount + 1 : loopCount,
              Optional.empty(),
              Optional.empty());
      default ->
          new StageInstance(
              runId,
              stageId,
              taskId,
              next,
              attempt,
              loopCount,
              startedAt,
              next.isSettled() ? Optional.of(now) : endedAt);
    };
  }
}
