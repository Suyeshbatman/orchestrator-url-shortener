package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.CodeIndexTool;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Brownfield only (the workflow skips it otherwise): spec + task graph + a map of the run's
 * workspace ({@link CodeIndexTool}) → impact_report.
 */
public final class ImpactAnalysisAgent extends LlmAgent {

  public static final String NAME = "ImpactAnalysisAgent";

  public ImpactAnalysisAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.IMPACT_REPORT, 6_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    Workspace workspace = ctx.tools().require(Workspace.class);
    return Map.of(
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "taskGraph", content(ctx, ArtifactTypes.TASK_GRAPH),
        "repoMap", new CodeIndexTool(workspace.root()).index().toMarkdown());
  }

  @Override
  protected String summary(JsonNode impact) {
    return size(impact, "files")
        + " files in "
        + size(impact, "modules")
        + " module(s), "
        + size(impact, "migrations")
        + " migration(s), "
        + size(impact, "testsToUpdate")
        + " test(s) to update";
  }
}
