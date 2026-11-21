package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.Authorizer;
import com.acme.orchestrator.agents.tools.FileTool;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * spec + design → test_patch, written to the workspace. It runs in parallel with the implementation
 * and never reads it: tests written from the contract, not from the code, don't inherit the code's
 * mistakes (confirmation bias).
 */
public final class TestAuthoringAgent extends LlmAgent {

  public static final String NAME = "TestAuthoringAgent";

  public TestAuthoringAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.TEST_PATCH, 24_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    return Map.of(
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "design", content(ctx, ArtifactTypes.DESIGN_DOC),
        "codebase", codebase(ctx));
  }

  @Override
  protected void after(StageContext ctx, JsonNode tests) {
    Patches.apply(new FileTool(ctx.tools().require(Workspace.class), Authorizer.of(ctx)), tests);
  }

  @Override
  protected String summary(JsonNode tests) {
    Set<String> covered = new TreeSet<>();
    tests.path("files").forEach(f -> f.path("covers").forEach(c -> covered.add(c.asString(""))));
    return size(tests, "files") + " test file(s) covering " + String.join(", ", covered);
  }
}
