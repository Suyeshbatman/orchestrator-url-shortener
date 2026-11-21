package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.agents.tools.DiffTool;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Everything the run produced → release_report: the checklist the {@code release.checklistComplete}
 * gate reads, changelog, risks, rollback plan and the final engineering summary, for the human who
 * approves the merge.
 */
public final class ReleaseAgent extends LlmAgent {

  public static final String NAME = "ReleaseAgent";

  public ReleaseAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.RELEASE_REPORT, 12_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    DiffTool.Result diff = new DiffTool(ctx.tools().require(Workspace.class)).diff();
    return Map.of(
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "design", content(ctx, ArtifactTypes.DESIGN_DOC),
        "validation", content(ctx, ArtifactTypes.VALIDATION_REPORT),
        "docs", ctx.artifact(ArtifactTypes.DOCS).map(a -> a.contentJson()).orElse("None."),
        "diffStats",
            diff.filesChanged()
                + " files, "
                + diff.linesChanged()
                + " lines changed: "
                + String.join(", ", diff.paths()));
  }

  @Override
  protected String summary(JsonNode report) {
    long done = 0;
    for (JsonNode item : report.path("checklist")) {
      String status = item.path("status").asString("");
      if (status.equals("DONE") || status.equals("NOT_APPLICABLE")) {
        done++;
      }
    }
    return done
        + " of "
        + size(report, "checklist")
        + " checklist items done, "
        + size(report, "openIssues")
        + " open issue(s)";
  }
}
