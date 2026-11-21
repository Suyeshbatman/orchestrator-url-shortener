package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.core.engine.StageContext;

/** Where LLM calls are charged: the run's budget, through the stage context. */
public interface UsageMeter {

  /** For tools and tests outside a run. */
  UsageMeter NONE =
      new UsageMeter() {
        @Override
        public void beforeCall() {}

        @Override
        public void record(long inputTokens, long outputTokens) {}
      };

  /**
   * @throws com.acme.orchestrator.core.budget.BudgetExceededException when the budget is used up
   */
  void beforeCall();

  void record(long inputTokens, long outputTokens);

  static UsageMeter of(StageContext ctx) {
    return new UsageMeter() {
      @Override
      public void beforeCall() {
        ctx.beforeLlmCall();
      }

      @Override
      public void record(long inputTokens, long outputTokens) {
        ctx.recordLlmCall(inputTokens, outputTokens);
      }
    };
  }
}
