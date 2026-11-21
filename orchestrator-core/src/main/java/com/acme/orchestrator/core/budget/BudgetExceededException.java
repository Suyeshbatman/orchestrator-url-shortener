package com.acme.orchestrator.core.budget;

/** An agent tried to spend LLM budget the run no longer has. The run is safe-stopped. */
public final class BudgetExceededException extends RuntimeException {

  public BudgetExceededException(String message) {
    super(message);
  }
}
