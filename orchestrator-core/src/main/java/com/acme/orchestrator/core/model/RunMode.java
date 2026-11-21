package com.acme.orchestrator.core.model;

public enum RunMode {
  /** New feature on a blank slate. */
  GREENFIELD,
  /** Change to existing code: impact analysis runs. */
  BROWNFIELD,
  /** The orchestrator decides from the requirement. */
  AUTO
}
