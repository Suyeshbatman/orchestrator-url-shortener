package com.acme.orchestrator.core.gate;

import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.GateResult;
import java.util.Optional;

/** One named check. Gates are read-only: they inspect the context and return a verdict. */
public interface Gate {

  /**
   * @param argument the part after the colon ({@code 0.80} in {@code coverage.min:0.80}), already
   *     accepted by {@link #checkArgument}
   */
  GateResult evaluate(Optional<String> argument, GateContext context);

  /**
   * Called when a workflow is loaded. By default a gate takes no argument.
   *
   * @throws IllegalArgumentException if the argument is unacceptable
   */
  default void checkArgument(Optional<String> argument) {
    argument.ifPresent(a -> {
      throw new IllegalArgumentException("takes no argument, got '" + a + "'");
    });
  }
}
