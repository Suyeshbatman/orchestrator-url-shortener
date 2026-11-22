package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.Authorizer;
import com.acme.orchestrator.agents.tools.FileTool;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Two passes over docs/: the draft from spec and design ({@code docs_draft}, in parallel with the
 * code), and the final version corrected with the validation results ({@code docs_finalize}).
 */
public final class DocsAgent extends LlmAgent {

  public static final String NAME = "DocsAgent";
  static final String FINALIZE_STAGE = "docs_finalize";

  public DocsAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.DOCS, 16_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    boolean finalPass = ctx.stageKey().equals(FINALIZE_STAGE);
    return Map.of(
        "phase", finalPass ? "final" : "draft",
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "design", content(ctx, ArtifactTypes.DESIGN_DOC),
        "previous",
            finalPass
                ? ctx.artifact(ArtifactTypes.DOCS).map(a -> a.contentJson()).orElse("None.")
                : "None (this is the draft).",
        "validation",
            finalPass
                ? content(ctx, ArtifactTypes.VALIDATION_REPORT)
                : "Not available yet (the code is still being written).");
  }

  @Override
  protected void after(StageContext ctx, JsonNode docs) {
    Patches.apply(
        new FileTool(
            ctx.tools().require(Workspace.class), Authorizer.of(ctx), kit.unreviewedOutput()),
        docs);
  }

  @Override
  protected String summary(JsonNode docs) {
    return docs.path("summary").asString("") + " (" + size(docs, "files") + " file(s))";
  }
}
