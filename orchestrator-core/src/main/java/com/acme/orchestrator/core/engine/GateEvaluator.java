package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.model.GateRef;

/**
 * Port: evaluates one named gate. The real implementation is {@code gate.GateRegistry}; {@link
 * #PASS_ALL} is for tests of the engine itself. Throwing counts as a failed gate.
 */
@FunctionalInterface
public interface GateEvaluator {

  GateEvaluator PASS_ALL = (gate, context) -> GateResult.pass("no gate checks configured");

  GateResult evaluate(GateRef gate, GateContext context);
}
