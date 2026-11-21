package com.acme.orchestrator.core.engine;

import com.acme.orchestrator.core.model.Executor;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves a stage's executor (agent or action name) to the {@link Agent} that runs it. */
public final class AgentRegistry {

  private final Map<String, Agent> byName;

  private AgentRegistry(Map<String, Agent> byName) {
    this.byName = Map.copyOf(byName);
  }

  public static AgentRegistry of(Map<String, Agent> byName) {
    return new AgentRegistry(byName);
  }

  public Optional<Agent> find(Executor executor) {
    return Optional.ofNullable(byName.get(executor.name()));
  }

  /** Executor names the workflow uses that have no agent. */
  public List<String> missing(WorkflowDefinition workflow) {
    return workflow.stages().stream()
        .map(StageDefinition::executor)
        .map(Executor::name)
        .distinct()
        .filter(name -> !byName.containsKey(name))
        .toList();
  }
}
