package com.acme.orchestrator.core.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A validated workflow (see {@link WorkflowLoader}). Stages keep their declaration order. */
public record WorkflowDefinition(
    String id, int version, Budgets budgets, List<StageDefinition> stages) {

  public WorkflowDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(budgets, "budgets");
    stages = List.copyOf(stages);
  }

  public Optional<StageDefinition> stage(String stageId) {
    return stages.stream().filter(s -> s.id().equals(stageId)).findFirst();
  }

  public Map<String, StageDefinition> stagesById() {
    Map<String, StageDefinition> byId = new LinkedHashMap<>();
    stages.forEach(s -> byId.put(s.id(), s));
    return byId;
  }
}
