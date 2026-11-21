package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.Authorizer;
import com.acme.orchestrator.agents.tools.FileTool;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.AgentValidationException;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * spec + tasks + approved design (+ impact, current files, the last validation report) → patch,
 * applied to the run's workspace through {@link FileTool} (every path policy-checked). The whole
 * change is judged as a diff by the validation stage, where a DENY loops back here with feedback.
 *
 * <p>Fallback {@code minimal-patch}: after retries, a narrower attempt that may change only one
 * file with the smallest change that satisfies the acceptance criteria.
 */
public final class ImplementationAgent extends LlmAgent {

  public static final String NAME = "ImplementationAgent";
  static final String MINIMAL_PATCH = "minimal-patch";

  public ImplementationAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.PATCH, 32_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    FileTool files = files(ctx);
    JsonNode graph = JSON.readTree(content(ctx, ArtifactTypes.TASK_GRAPH));
    String impact =
        ctx.artifact(ArtifactTypes.IMPACT_REPORT)
            .map(a -> a.contentJson())
            .orElse("None (greenfield).");
    List<String> paths = new ArrayList<>();
    graph
        .path("tasks")
        .forEach(t -> t.path("estimatedFiles").forEach(f -> paths.add(f.asString(""))));
    JSON.readTree(impact.startsWith("{") ? impact : "{}")
        .path("files")
        .forEach(f -> paths.add(f.path("path").asString("")));
    return Map.of(
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "taskGraph", graph.toString(),
        "design", content(ctx, ArtifactTypes.DESIGN_DOC),
        "impact", impact,
        "files", Patches.currentContent(files, paths),
        "validation",
            ctx.artifact(ArtifactTypes.VALIDATION_REPORT)
                .map(a -> a.contentJson())
                .orElse("None yet (first implementation)."),
        "strategy", strategy(ctx),
        "codebase", codebase(ctx));
  }

  @Override
  protected void check(StageContext ctx, JsonNode patch) {
    if (minimal(ctx) && patch.path("files").size() + patch.path("deletions").size() != 1) {
      throw new AgentValidationException(
          "the minimal-patch fallback must change exactly one file, not "
              + (patch.path("files").size() + patch.path("deletions").size()));
    }
  }

  @Override
  protected void after(StageContext ctx, JsonNode patch) {
    Patches.apply(files(ctx), patch);
  }

  @Override
  protected String summary(JsonNode patch) {
    return patch.path("summary").asString("")
        + " ("
        + size(patch, "files")
        + " file(s) written, "
        + size(patch, "deletions")
        + " deleted)";
  }

  private static String strategy(StageContext ctx) {
    return minimal(ctx)
        ? "This is a fallback attempt after earlier attempts failed: change exactly ONE file, with"
            + " the smallest change that satisfies the acceptance criteria. Leave everything else"
            + " as it is."
        : "Implement every task in the task graph.";
  }

  private static boolean minimal(StageContext ctx) {
    return ctx.fallbackStrategy().filter(MINIMAL_PATCH::equals).isPresent();
  }

  private static FileTool files(StageContext ctx) {
    return new FileTool(ctx.tools().require(Workspace.class), Authorizer.of(ctx));
  }
}
