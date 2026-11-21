package com.acme.orchestrator.core.dag;

import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import java.util.List;

/** Builds the stage graph of a workflow: one node per stage id, one edge per dependsOn entry. */
public final class WorkflowGraph {

  private WorkflowGraph() {}

  public static Dag<String> of(WorkflowDefinition workflow) {
    return of(workflow.stages());
  }

  /** Dependencies must name stages in the list (the loader guarantees that). */
  public static Dag<String> of(List<StageDefinition> stages) {
    Dag<String> dag = new Dag<>();
    stages.forEach(s -> dag.addNode(s.id()));
    for (StageDefinition s : stages) {
      for (String dep : s.dependsOn()) {
        dag.addEdge(dep, s.id());
      }
    }
    return dag;
  }
}
