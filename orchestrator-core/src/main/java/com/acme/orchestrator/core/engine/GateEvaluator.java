package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.model.GateRef;

/**
 * Port: evaluates one named gate. The registry of real gates (input.nonEmpty, coverage.min, …)
 * arrives in Step 4.1; until then {@link #PASS_ALL}. Throwing counts as a failed gate.
 */
@FunctionalInterface
public interface GateEvaluator {

  GateEvaluator PASS_ALL = (gate, context) -> GateResult.pass("no gate checks configured");

  GateResult evaluate(GateRef gate, GateContext context);
}
