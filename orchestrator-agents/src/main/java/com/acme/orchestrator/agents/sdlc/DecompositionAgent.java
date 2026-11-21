package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * requirements_spec → task_graph. Coverage and acyclicity are the exit gates' job ({@code
 * taskGraph.everyRequirementCovered}, {@code taskGraph.acyclic}); a failure comes back as feedback.
 */
public final class DecompositionAgent extends LlmAgent {

  public static final String NAME = "DecompositionAgent";

  public DecompositionAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.TASK_GRAPH, 6_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    return Map.of("spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC));
  }

  @Override
  protected String summary(JsonNode graph) {
    long high = 0;
    for (JsonNode t : graph.path("tasks")) {
      if ("HIGH".equals(t.path("risk").asString(""))) {
        high++;
      }
    }
    return size(graph, "tasks") + " tasks (" + high + " high risk)";
  }
}
