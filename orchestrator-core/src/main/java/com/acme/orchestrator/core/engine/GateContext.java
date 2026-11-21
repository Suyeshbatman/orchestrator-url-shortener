package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.StageDefinition;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a gate may look at.
 *
 * @param produced artifacts this attempt produced (empty for entry gates)
 * @param latestOfType read-only lookup of the run's artifacts by type
 */
public record GateContext(
    Run run,
    StageDefinition stage,
    Phase phase,
    List<Artifact> produced,
    Function<String, Optional<Artifact>> latestOfType) {

  public enum Phase {
    ENTRY,
    EXIT
  }

  public GateContext {
    produced = List.copyOf(produced);
  }
}
