package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * spec + task graph (+ impact report for brownfield) → design_doc, and the design's risk score,
 * which decides at L2 whether a human must approve it (below 30: no).
 */
public final class DesignAgent extends LlmAgent {

  public static final String NAME = "DesignAgent";

  public DesignAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.DESIGN_DOC, 10_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    return Map.of(
        "spec", content(ctx, ArtifactTypes.REQUIREMENTS_SPEC),
        "taskGraph", content(ctx, ArtifactTypes.TASK_GRAPH),
        "impact",
            ctx.artifact(ArtifactTypes.IMPACT_REPORT)
                .map(a -> a.contentJson())
                .orElse("Not applicable: a greenfield change (no impact analysis ran)."));
  }

  @Override
  protected void after(StageContext ctx, JsonNode design) {
    ctx.reportRisk(riskScore(design));
  }

  @Override
  protected String summary(JsonNode design) {
    return size(design, "components")
        + " component(s), "
        + size(design, "risks")
        + " risk(s), risk score "
        + riskScore(design)
        + (design.path("schemaChanges").asString("").isBlank() ? "" : ", with a schema change");
  }

  /**
   * 0–100: the worst risk's likelihood × impact (LOW 1, MED 2, HIGH 3) × 10, plus 20 for a schema
   * change (migrations are hard to undo). One LOW/LOW risk scores 10; MED/MED 40; HIGH/HIGH 90.
   */
  public static int riskScore(JsonNode design) {
    int worst = 0;
    for (JsonNode risk : design.path("risks")) {
      worst =
          Math.max(
              worst,
              level(risk.path("likelihood").asString(""))
                  * level(risk.path("impact").asString("")));
    }
    int score = worst * 10 + (design.path("schemaChanges").asString("").isBlank() ? 0 : 20);
    return Math.min(100, score);
  }

  private static int level(String rating) {
    return switch (rating) {
      case "HIGH" -> 3;
      case "MED" -> 2;
      case "LOW" -> 1;
      default -> 0;
    };
  }
}
