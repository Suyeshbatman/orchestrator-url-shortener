package com.acme.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StageInstanceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock AT_T0 = Clock.fixed(T0, ZoneOffset.UTC);
  private static final Clock AT_T1 = Clock.fixed(T0.plusSeconds(30), ZoneOffset.UTC);

  @Test
  void startCountsAttemptsAndKeepsTheFirstStartTime() {
    StageInstance s =
        StageInstance.pending("run-1", "implementation")
            .apply(StageEvent.DEPENDENCIES_MET, AT_T0)
            .apply(StageEvent.START, AT_T0)
            .apply(StageEvent.SCHEDULE_RETRY, AT_T0)
            .apply(StageEvent.START, AT_T1);

    assertThat(s.status()).isEqualTo(StageStatus.RUNNING);
    assertThat(s.attempt()).isEqualTo(2);
    assertThat(s.startedAt()).contains(T0);
    assertThat(s.endedAt()).isEmpty();
  }

  @Test
  void settlingStampsTheEndTime() {
    StageInstance s =
        StageInstance.pending("run-1", "design")
            .apply(StageEvent.DEPENDENCIES_MET, AT_T0)
            .apply(StageEvent.START, AT_T0)
            .apply(StageEvent.AWAIT_APPROVAL, AT_T0);
    assertThat(s.endedAt()).isEmpty(); // waiting is not settled

    StageInstance approved = s.apply(StageEvent.APPROVE, AT_T1);

    assertThat(approved.status()).isEqualTo(StageStatus.SUCCEEDED);
    assertThat(approved.endedAt()).contains(T0.plusSeconds(30));
  }

  @Test
  void resetAfterInvalidationStartsANewLoop() {
    StageInstance done =
        StageInstance.pending("run-1", "implementation")
            .apply(StageEvent.DEPENDENCIES_MET, AT_T0)
            .apply(StageEvent.START, AT_T0)
            .apply(StageEvent.SUCCEED, AT_T0);

    StageInstance again = done.apply(StageEvent.INVALIDATE, AT_T1).apply(StageEvent.RESET, AT_T1);

    assertThat(again.status()).isEqualTo(StageStatus.PENDING);
    assertThat(again.loopCount()).isEqualTo(1);
    assertThat(again.attempt()).isZero();
    assertThat(again.startedAt()).isEmpty();
    assertThat(again.endedAt()).isEmpty();
  }

  @Test
  void resumingAHaltedStageIsNotALoop() {
    StageInstance resumed =
        StageInstance.pending("run-1", "design")
            .apply(StageEvent.HALT, AT_T0)
            .apply(StageEvent.RESET, AT_T1);

    assertThat(resumed.status()).isEqualTo(StageStatus.PENDING);
    assertThat(resumed.loopCount()).isZero();
  }

  @Test
  void illegalEventsThrowAndLeaveTheInstanceUnchanged() {
    StageInstance pending = StageInstance.pending("run-1", "design");

    assertThatThrownBy(() -> pending.apply(StageEvent.SUCCEED, AT_T0))
        .isInstanceOf(IllegalTransitionException.class);
    assertThat(pending.status()).isEqualTo(StageStatus.PENDING);
  }

  @Test
  void keyIncludesTheTaskForExpandedTasks() {
    assertThat(StageInstance.pending("r", "design").key()).isEqualTo("design");
    assertThat(StageInstance.pendingTask("r", "implementation", "T-2").key())
        .isEqualTo("implementation/T-2");
  }
}
