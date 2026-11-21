package com.acme.orchestrator.core.budget;

import com.acme.orchestrator.core.model.Budgets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A run's budgets (wall clock, LLM calls, tokens) and its running consumption. Agents charge LLM
 * use through it as they go (so one runaway attempt can't overspend); the coordinator checks it
 * after every step and when the wall-clock budget runs out, and safe-stops the run when it's
 * exceeded. Thread-safe.
 */
public final class BudgetGuard {

  /** The first budget found exceeded. */
  public record Exceeded(String budget, String limit, String used) {
    public String describe() {
      return budget + " budget exceeded: used " + used + " of " + limit;
    }
  }

  private final Budgets budgets;
  private final AtomicLong calls = new AtomicLong();
  private final AtomicLong tokens = new AtomicLong();

  /**
   * @param spent what the run already used (summed from its log, so a restart doesn't forget it)
   */
  public BudgetGuard(Budgets budgets, Usage spent) {
    this.budgets = budgets;
    calls.set(spent.llmCalls());
    tokens.set(spent.tokens());
  }

  public Budgets budgets() {
    return budgets;
  }

  public Usage used() {
    return new Usage(calls.get(), tokens.get());
  }

  /**
   * Call before each LLM call.
   *
   * @throws BudgetExceededException if no call or token is left
   */
  public void beforeLlmCall() {
    if (calls.get() >= budgets.maxLlmCalls()) {
      throw new BudgetExceededException(
          "LLM call budget used up (" + calls.get() + " of " + budgets.maxLlmCalls() + ")");
    }
    if (tokens.get() >= budgets.maxTokens()) {
      throw new BudgetExceededException(
          "token budget used up (" + tokens.get() + " of " + budgets.maxTokens() + ")");
    }
  }

  /** Call after each LLM call. */
  public void charge(Usage usage) {
    calls.addAndGet(usage.llmCalls());
    tokens.addAndGet(usage.tokens());
  }

  /** The first exceeded budget, if any, given how long the run has existed. */
  public Optional<Exceeded> check(Duration elapsed) {
    if (calls.get() > budgets.maxLlmCalls()) {
      return Optional.of(
          new Exceeded("llmCalls", String.valueOf(budgets.maxLlmCalls()), String.valueOf(calls)));
    }
    if (tokens.get() > budgets.maxTokens()) {
      return Optional.of(
          new Exceeded("tokens", String.valueOf(budgets.maxTokens()), String.valueOf(tokens)));
    }
    if (elapsed.compareTo(budgets.maxWallClock()) >= 0) {
      return Optional.of(
          new Exceeded("wallClock", budgets.maxWallClock().toString(), elapsed.toString()));
    }
    return Optional.empty();
  }

  /** Time left on the wall-clock budget (zero when it's up). */
  public Duration wallClockLeft(Duration elapsed) {
    Duration left = budgets.maxWallClock().minus(elapsed);
    return left.isNegative() ? Duration.ZERO : left;
  }
}
