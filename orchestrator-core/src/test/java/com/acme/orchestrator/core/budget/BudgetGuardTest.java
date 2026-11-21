package com.acme.orchestrator.core.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.Budgets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BudgetGuardTest {

  private static final Budgets BUDGETS = new Budgets(Duration.ofMinutes(30), 3, 1000);

  @Test
  void callsAreAllowedUntilTheBudgetIsUsedUp() {
    BudgetGuard guard = new BudgetGuard(BUDGETS, new Usage(2, 100));

    assertThatCode(guard::beforeLlmCall).doesNotThrowAnyException();
    guard.charge(new Usage(1, 50));
    assertThatThrownBy(guard::beforeLlmCall)
        .isInstanceOf(BudgetExceededException.class)
        .hasMessage("LLM call budget used up (3 of 3)");
    assertThat(guard.used()).isEqualTo(new Usage(3, 150));
    assertThat(guard.check(Duration.ZERO)).isEmpty(); // used up, not exceeded
  }

  @Test
  void tokensAndWallClockAreChecked() {
    BudgetGuard guard = new BudgetGuard(BUDGETS, Usage.NONE);
    guard.charge(new Usage(1, 1000));

    assertThatThrownBy(guard::beforeLlmCall).hasMessage("token budget used up (1000 of 1000)");
    guard.charge(new Usage(0, 1));
    assertThat(guard.check(Duration.ZERO))
        .contains(new BudgetGuard.Exceeded("tokens", "1000", "1001"));
    assertThat(new BudgetGuard(BUDGETS, Usage.NONE).check(Duration.ofMinutes(30)))
        .hasValueSatisfying(e ->
            assertThat(e.describe()).isEqualTo("wallClock budget exceeded: used PT30M of PT30M"));
  }

  @Test
  void wallClockLeftNeverGoesNegative() {
    BudgetGuard guard = new BudgetGuard(BUDGETS, Usage.NONE);

    assertThat(guard.wallClockLeft(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(20));
    assertThat(guard.wallClockLeft(Duration.ofHours(1))).isZero();
  }

  @Test
  void usageAddsUpAndIsNeverNegative() {
    assertThat(new Usage(1, 10).plus(new Usage(2, 5))).isEqualTo(new Usage(3, 15));
    assertThatThrownBy(() -> new Usage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
