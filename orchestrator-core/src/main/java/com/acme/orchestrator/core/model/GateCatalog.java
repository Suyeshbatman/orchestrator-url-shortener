package com.acme.orchestrator.core.model;

/**
 * The gates a workflow may name. {@link WorkflowLoader} checks every gate against it, so an unknown
 * gate (or a bad argument such as {@code coverage.min:lots}) is a load error, not a runtime
 * surprise.
 */
@FunctionalInterface
public interface GateCatalog {

  /** Accepts any syntactically valid gate (for tests and tooling that only parse). */
  GateCatalog ANY = gate -> {};

  /**
   * @throws IllegalArgumentException naming the problem, if the gate is unknown or its argument is
   *     unacceptable
   */
  void check(GateRef gate);
}
