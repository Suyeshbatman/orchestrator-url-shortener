package com.acme.orchestrator.core.event;

import static com.acme.orchestrator.core.event.EventType.APPROVAL_GRANTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REQUESTED;
import static com.acme.orchestrator.core.event.EventType.ARTIFACT_PRODUCED;
import static com.acme.orchestrator.core.event.EventType.GATE_EVALUATED;
import static com.acme.orchestrator.core.event.EventType.RUN_COMPLETED;
import static com.acme.orchestrator.core.event.EventType.RUN_CREATED;
import static com.acme.orchestrator.core.event.EventType.RUN_HALTED;
import static com.acme.orchestrator.core.event.EventType.RUN_RESUMED;
import static com.acme.orchestrator.core.event.EventType.STAGE_FAILED;
import static com.acme.orchestrator.core.event.EventType.STAGE_INVALIDATED;
import static com.acme.orchestrator.core.event.EventType.STAGE_READY;
import static com.acme.orchestrator.core.event.EventType.STAGE_RETRIED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SKIPPED;
import static com.acme.orchestrator.core.event.EventType.STAGE_STARTED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Budgets;
import com.acme.orchestrator.core.model.IllegalTransitionException;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunStateProjectorTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final String RUN = "run-1";

  private final MutableClock clock = new MutableClock(T0);
  private final InMemoryEventStore store = new InMemoryEventStore(clock);
  private final Run run =
      new Run(
          RUN,
          "sdlc-standard",
          RunMode.BROWNFIELD,
          AutonomyLevel.L1_SUPERVISED,
          RunStatus.CREATED,
          new Budgets(Duration.ofMinutes(30), 60, 400_000));

  /** Appends one event, one second after the previous one. Returns its seq. */
  private long emit(EventType type, String stage, Map<String, ?> payload) {
    clock.advance(Duration.ofSeconds(1));
    EventDraft draft =
        stage == null
            ? EventDraft.run(RUN, type, Actor.SYSTEM, payload)
            : EventDraft.stage(RUN, type, stage, Actor.SYSTEM, payload);
    return store.append(draft).seq();
  }

  private long emit(EventType type, String stage) {
    return emit(type, stage, Map.of());
  }

  private long task(EventType type, String task) {
    return emit(type, "implementation", Map.of(RunStateProjector.TASK_ID, task));
  }

  private RunState upTo(long seq) {
    return RunStateProjector.project(store.events(RUN).subList(0, (int) seq));
  }

  @Test
  void rebuildsAFullRunAndEveryIntermediateState() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    // requirements needs approval
    emit(STAGE_READY, "requirements");
    emit(STAGE_STARTED, "requirements");
    emit(ARTIFACT_PRODUCED, "requirements", Map.of("artifactId", "a1")); // audit-only
    long awaiting = emit(APPROVAL_REQUESTED, "requirements");
    emit(APPROVAL_GRANTED, "requirements");
    // impact analysis skipped (GREENFIELD-style condition false)
    emit(STAGE_SKIPPED, "impact_analysis");
    // implementation expanded into two tasks; T1 needs a retry
    task(STAGE_READY, "T1");
    task(STAGE_READY, "T2");
    task(STAGE_STARTED, "T1");
    task(STAGE_STARTED, "T2");
    long retrying = task(STAGE_RETRIED, "T1");
    task(STAGE_STARTED, "T1");
    task(STAGE_SUCCEEDED, "T1");
    task(STAGE_SUCCEEDED, "T2");
    // validation fails and loops back to implementation T1
    emit(STAGE_READY, "validation");
    emit(STAGE_STARTED, "validation");
    emit(GATE_EVALUATED, "validation", Map.of("gate", "tests.green", "passed", false));
    emit(STAGE_FAILED, "validation");
    emit(STAGE_INVALIDATED, "validation");
    task(STAGE_INVALIDATED, "T1");
    long looped = task(STAGE_READY, "T1"); // implicit reset: a new loop
    // operator halts, then resumes
    long halted = emit(RUN_HALTED, null, Map.of("reason", "operator"));
    emit(RUN_RESUMED, null);
    task(STAGE_READY, "T1");
    task(STAGE_STARTED, "T1");
    task(STAGE_SUCCEEDED, "T1");
    emit(STAGE_READY, "validation");
    emit(STAGE_STARTED, "validation");
    emit(STAGE_SUCCEEDED, "validation");
    long done = emit(RUN_COMPLETED, null);

    // Just created
    RunState start = upTo(1);
    assertThat(start.run()).isEqualTo(run);
    assertThat(start.stages()).isEmpty();

    // Waiting for a human while nothing else can run
    RunState waiting = upTo(awaiting);
    assertThat(waiting.run().status()).isEqualTo(RunStatus.AWAITING_HUMAN);
    assertThat(waiting.statuses()).containsEntry("requirements", StageStatus.WAITING_APPROVAL);

    // Retry in progress
    RunState retry = upTo(retrying);
    assertThat(retry.run().status()).isEqualTo(RunStatus.RUNNING);
    assertThat(retry.stages().get("implementation/T1").status()).isEqualTo(StageStatus.RETRYING);
    assertThat(retry.stages().get("implementation/T1").attempt()).isEqualTo(1);
    assertThat(retry.statuses())
        .containsEntry("requirements", StageStatus.SUCCEEDED)
        .containsEntry("impact_analysis", StageStatus.SKIPPED)
        .containsEntry("implementation/T2", StageStatus.RUNNING);

    // Loop-back: T1 starts loop 1 with fresh counters
    StageInstance t1Looped = upTo(looped).stages().get("implementation/T1");
    assertThat(t1Looped.status()).isEqualTo(StageStatus.READY);
    assertThat(t1Looped.loopCount()).isEqualTo(1);
    assertThat(t1Looped.attempt()).isZero();
    assertThat(upTo(looped).statuses()).containsEntry("validation", StageStatus.INVALIDATED);

    // Halt stops what was in progress, leaves settled work alone
    RunState stopped = upTo(halted);
    assertThat(stopped.run().status()).isEqualTo(RunStatus.HALTED);
    assertThat(stopped.statuses())
        .containsEntry("implementation/T1", StageStatus.HALTED)
        .containsEntry("implementation/T2", StageStatus.SUCCEEDED)
        .containsEntry("validation", StageStatus.INVALIDATED);

    // The end
    RunState end = upTo(done);
    assertThat(end.run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(end.lastSeq()).isEqualTo(done);
    assertThat(end.headHash()).isEqualTo(store.head(RUN).orElseThrow().hash());
    assertThat(end.statuses())
        .containsExactly(
            Map.entry("requirements", StageStatus.SUCCEEDED),
            Map.entry("impact_analysis", StageStatus.SKIPPED),
            Map.entry("implementation/T1", StageStatus.SUCCEEDED),
            Map.entry("implementation/T2", StageStatus.SUCCEEDED),
            Map.entry("validation", StageStatus.SUCCEEDED));
    StageInstance t1 = end.stages().get("implementation/T1");
    assertThat(t1.loopCount()).isEqualTo(1); // resume is not a loop
    assertThat(t1.attempt()).isEqualTo(1);
    assertThat(t1.taskId()).contains("T1");
    StageInstance requirements = end.stages().get("requirements");
    assertThat(requirements.startedAt()).contains(T0.plusSeconds(3)); // from the events
    assertThat(requirements.endedAt()).contains(T0.plusSeconds(6));
  }

  @Test
  void replayIsDeterministicSoCrashRecoveryGetsTheSameState() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    emit(STAGE_READY, "requirements");
    emit(STAGE_STARTED, "requirements");
    List<Event> log = store.events(RUN);

    clock.advance(Duration.ofHours(1)); // wall clock moves on; the projection must not care
    assertThat(RunStateProjector.project(List.copyOf(log)))
        .isEqualTo(RunStateProjector.project(log));
  }

  @Test
  void parallelWorkKeepsTheRunRunningEvenWhileAnApprovalWaits() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    emit(STAGE_READY, "design");
    emit(STAGE_STARTED, "design");
    emit(APPROVAL_REQUESTED, "design");
    long both = emit(STAGE_READY, "docs_draft");

    assertThat(upTo(both).run().status()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void runLevelRollbackAndFailure() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    emit(STAGE_READY, "implementation");
    emit(STAGE_STARTED, "implementation");
    emit(STAGE_SUCCEEDED, "implementation");
    long stageRollback = emit(EventType.ROLLED_BACK, "implementation");
    long runRollback = emit(EventType.ROLLED_BACK, null);

    assertThat(upTo(stageRollback).statuses())
        .containsEntry("implementation", StageStatus.ROLLED_BACK);
    assertThat(upTo(runRollback).run().status()).isEqualTo(RunStatus.ROLLED_BACK);
  }

  @Test
  void aCorruptLogIsRejectedNotGuessedAt() {
    assertThatThrownBy(() -> RunStateProjector.project(List.of()))
        .hasMessageContaining("must start with RUN_CREATED");

    emit(STAGE_READY, "design");
    assertThatThrownBy(() -> RunStateProjector.project(store.events(RUN)))
        .hasMessageContaining("must start with RUN_CREATED");
  }

  @Test
  void anIllegalTransitionInTheLogThrows() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    emit(STAGE_SUCCEEDED, "design"); // never started

    assertThatThrownBy(() -> RunStateProjector.project(store.events(RUN)))
        .isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  void stageEventsNeedAStageAndRunCreatedNeedsItsPayload() {
    emit(RUN_CREATED, null, RunStateProjector.runCreatedPayload(run));
    emit(STAGE_READY, null);
    assertThatThrownBy(() -> RunStateProjector.project(store.events(RUN)))
        .hasMessageContaining("has no stage");

    InMemoryEventStore other = new InMemoryEventStore(clock);
    other.append(EventDraft.run("r2", RUN_CREATED, Actor.SYSTEM, Map.of("mode", "GREENFIELD")));
    assertThatThrownBy(() -> RunStateProjector.project(other.events("r2")))
        .hasMessageContaining("RUN_CREATED payload is incomplete");
  }
}
