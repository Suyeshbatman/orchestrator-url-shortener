package com.acme.orchestrator.core.model;

/** A {@link StageEvent} that isn't allowed in the stage's current {@link StageStatus}. */
public final class IllegalTransitionException extends RuntimeException {

  private final StageStatus from;
  private final StageEvent event;

  public IllegalTransitionException(StageStatus from, StageEvent event) {
    super("illegal stage transition: " + event + " in " + from);
    this.from = from;
    this.event = event;
  }

  public StageStatus from() {
    return from;
  }

  public StageEvent event() {
    return event;
  }
}
