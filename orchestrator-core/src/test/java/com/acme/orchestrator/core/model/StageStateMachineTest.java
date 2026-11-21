package com.acme.orchestrator.core.model;

import static com.acme.orchestrator.core.model.StageEvent.APPROVE;
import static com.acme.orchestrator.core.model.StageEvent.APPROVE_START;
import static com.acme.orchestrator.core.model.StageEvent.AWAIT_APPROVAL;
import static com.acme.orchestrator.core.model.StageEvent.DEPENDENCIES_MET;
import static com.acme.orchestrator.core.model.StageEvent.FAIL;
import static com.acme.orchestrator.core.model.StageEvent.HALT;
import static com.acme.orchestrator.core.model.StageEvent.INVALIDATE;
import static com.acme.orchestrator.core.model.StageEvent.REJECT;
import static com.acme.orchestrator.core.model.StageEvent.RESET;
import static com.acme.orchestrator.core.model.StageEvent.ROLL_BACK;
import static com.acme.orchestrator.core.model.StageEvent.SCHEDULE_RETRY;
import static com.acme.orchestrator.core.model.StageEvent.SKIP;
import static com.acme.orchestrator.core.model.StageEvent.START;
import static com.acme.orchestrator.core.model.StageEvent.SUCCEED;
import static com.acme.orchestrator.core.model.StageStatus.FAILED;
import static com.acme.orchestrator.core.model.StageStatus.HALTED;
import static com.acme.orchestrator.core.model.StageStatus.INVALIDATED;
import static com.acme.orchestrator.core.model.StageStatus.PENDING;
import static com.acme.orchestrator.core.model.StageStatus.READY;
import static com.acme.orchestrator.core.model.StageStatus.RETRYING;
import static com.acme.orchestrator.core.model.StageStatus.ROLLED_BACK;
import static com.acme.orchestrator.core.model.StageStatus.RUNNING;
import static com.acme.orchestrator.core.model.StageStatus.SKIPPED;
import static com.acme.orchestrator.core.model.StageStatus.SUCCEEDED;
import static com.acme.orchestrator.core.model.StageStatus.WAITING_APPROVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every (status, event) pair: the 38 legal ones must land where this independent copy of the table
 * says, and the other 116 must throw.
 */
class StageStateMachineTest {

  private static final Map<StageStatus, Map<StageEvent, StageStatus>> EXPECTED =
      Map.ofEntries(
          Map.entry(PENDING, Map.of(DEPENDENCIES_MET, READY, SKIP, SKIPPED, HALT, HALTED)),
          Map.entry(
              READY,
              Map.of(
                  START, RUNNING,
                  SKIP, SKIPPED,
                  FAIL, FAILED,
                  INVALIDATE, INVALIDATED,
                  HALT, HALTED,
                  ROLL_BACK, ROLLED_BACK,
                  AWAIT_APPROVAL, WAITING_APPROVAL)),
          Map.entry(
              RUNNING,
              Map.of(
                  SUCCEED, SUCCEEDED,
                  AWAIT_APPROVAL, WAITING_APPROVAL,
                  SCHEDULE_RETRY, RETRYING,
                  FAIL, FAILED,
                  INVALIDATE, INVALIDATED,
                  HALT, HALTED,
                  ROLL_BACK, ROLLED_BACK)),
          Map.entry(
              WAITING_APPROVAL,
              Map.of(
                  APPROVE, SUCCEEDED,
                  APPROVE_START, READY,
                  REJECT, RETRYING,
                  INVALIDATE, INVALIDATED,
                  HALT, HALTED,
                  ROLL_BACK, ROLLED_BACK)),
          Map.entry(
              RETRYING, Map.of(START, RUNNING, FAIL, FAILED, HALT, HALTED, ROLL_BACK, ROLLED_BACK)),
          Map.entry(SUCCEEDED, Map.of(INVALIDATE, INVALIDATED, ROLL_BACK, ROLLED_BACK)),
          Map.entry(FAILED, Map.of(INVALIDATE, INVALIDATED, ROLL_BACK, ROLLED_BACK)),
          Map.entry(SKIPPED, Map.of(INVALIDATE, INVALIDATED, ROLL_BACK, ROLLED_BACK)),
          Map.entry(INVALIDATED, Map.of(RESET, PENDING, ROLL_BACK, ROLLED_BACK)),
          Map.entry(HALTED, Map.of(RESET, PENDING, ROLL_BACK, ROLLED_BACK)),
          Map.entry(ROLLED_BACK, Map.of(RESET, PENDING)));

  static Stream<Arguments> legal() {
    List<Arguments> args = new ArrayList<>();
    EXPECTED.forEach(
        (from, events) -> events.forEach((e, to) -> args.add(Arguments.of(from, e, to))));
    return args.stream();
  }

  static Stream<Arguments> illegal() {
    List<Arguments> args = new ArrayList<>();
    for (StageStatus from : StageStatus.values()) {
      for (StageEvent e : StageEvent.values()) {
        if (!EXPECTED.get(from).containsKey(e)) {
          args.add(Arguments.of(from, e));
        }
      }
    }
    return args.stream();
  }

  @ParameterizedTest(name = "{0} --{1}--> {2}")
  @MethodSource("legal")
  void legalTransitions(StageStatus from, StageEvent event, StageStatus to) {
    assertThat(StageStateMachine.transition(from, event)).isEqualTo(to);
    assertThat(StageStateMachine.canTransition(from, event)).isTrue();
  }

  @ParameterizedTest(name = "{0} --{1}--> illegal")
  @MethodSource("illegal")
  void illegalTransitionsThrow(StageStatus from, StageEvent event) {
    assertThat(StageStateMachine.canTransition(from, event)).isFalse();
    assertThat(StageStateMachine.next(from, event)).isEmpty();
    assertThatThrownBy(() -> StageStateMachine.transition(from, event))
        .isInstanceOfSatisfying(
            IllegalTransitionException.class,
            e -> {
              assertThat(e.from()).isEqualTo(from);
              assertThat(e.event()).isEqualTo(event);
            })
        .hasMessageContaining(from.name())
        .hasMessageContaining(event.name());
  }

  @Test
  void tableCoversEveryStatusAndMatchesExactly() {
    assertThat(legal().count()).isEqualTo(38);
    assertThat(illegal().count())
        .isEqualTo((long) StageStatus.values().length * StageEvent.values().length - 38);
    for (StageStatus s : StageStatus.values()) {
      assertThat(StageStateMachine.allowed(s)).isEqualTo(EXPECTED.get(s));
    }
  }

  @Test
  void allowedIsUnmodifiable() {
    assertThatThrownBy(() -> StageStateMachine.allowed(PENDING).put(START, RUNNING))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void typicalLifecycles() {
    assertThat(run(PENDING, DEPENDENCIES_MET, START, SUCCEED)).isEqualTo(SUCCEEDED);
    assertThat(run(PENDING, DEPENDENCIES_MET, START, AWAIT_APPROVAL, APPROVE)).isEqualTo(SUCCEEDED);
    assertThat(run(PENDING, DEPENDENCIES_MET, START, SCHEDULE_RETRY, START, SUCCEED))
        .isEqualTo(SUCCEEDED);
    assertThat(run(PENDING, DEPENDENCIES_MET, SKIP)).isEqualTo(SKIPPED);
    // loop-back: validated work is discarded and re-run
    assertThat(run(SUCCEEDED, INVALIDATE, RESET, DEPENDENCIES_MET, START)).isEqualTo(RUNNING);
    // safe stop and resume
    assertThat(run(RUNNING, HALT, RESET)).isEqualTo(PENDING);
  }

  @Test
  void dependencySatisfactionAndSettledStatuses() {
    assertThat(SUCCEEDED.satisfiesDependency()).isTrue();
    assertThat(SKIPPED.satisfiesDependency()).isTrue();
    assertThat(FAILED.satisfiesDependency()).isFalse();
    assertThat(WAITING_APPROVAL.satisfiesDependency()).isFalse();

    assertThat(Stream.of(StageStatus.values()).filter(StageStatus::isSettled))
        .containsExactlyInAnyOrder(SUCCEEDED, FAILED, SKIPPED, ROLLED_BACK, HALTED);
  }

  private static StageStatus run(StageStatus start, StageEvent... events) {
    StageStatus s = start;
    for (StageEvent e : events) {
      s = StageStateMachine.transition(s, e);
    }
    return s;
  }
}
