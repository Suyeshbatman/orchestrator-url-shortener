package com.acme.orchestrator.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionTest {

  private static Run run(RunMode mode, AutonomyLevel level) {
    return new Run(
        "r", "wf", mode, level, RunStatus.RUNNING, new Budgets(Duration.ofMinutes(1), 1, 1));
  }

  @Test
  void equalityOnMode() {
    Condition brownfield = Condition.parse("run.mode == 'BROWNFIELD'");

    assertThat(brownfield.test(run(RunMode.BROWNFIELD, AutonomyLevel.L1_SUPERVISED))).isTrue();
    assertThat(brownfield.test(run(RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED))).isFalse();
  }

  @Test
  void inequalityOnAutonomyLevel() {
    Condition notTrusted = Condition.parse("  run.autonomyLevel!='L2_TRUSTED' ");

    assertThat(notTrusted.test(run(RunMode.AUTO, AutonomyLevel.L0_SUGGEST))).isTrue();
    assertThat(notTrusted.test(run(RunMode.AUTO, AutonomyLevel.L2_TRUSTED))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "run.mode = 'BROWNFIELD'",
        "run.mode == BROWNFIELD",
        "run.owner == 'alice'",
        "run.mode == 'SIDEWAYS'",
        "run.mode == 'BROWNFIELD' && run.mode == 'AUTO'",
        "System.exit(1)",
        ""
      })
  void anythingElseIsRejected(String expression) {
    assertThatThrownBy(() -> Condition.parse(expression))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theLoaderRejectsBadConditions() {
    assertThatThrownBy(() ->
            new WorkflowLoader()
                .parse(
                    """
                    workflow: t
                    budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 1 }
                    stages:
                      - id: a
                        agent: A
                        condition: "run.mode == 'SIDEWAYS'"
                    """,
                    "t.yml"))
        .isInstanceOfSatisfying(
            WorkflowValidationException.class,
            e ->
                assertThat(e.problems())
                    .singleElement()
                    .asString()
                    .startsWith("stages[0] (a).condition: 'SIDEWAYS' is not a valid value"));
  }
}
