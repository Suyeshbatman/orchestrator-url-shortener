package com.acme.orchestrator.agents.sdlc;

import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.model.ArtifactTypes;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * requirement (+ answers to earlier questions, if any) → requirements_spec. BLOCKING ambiguities
 * are left for the {@code spec.noBlockingQuestions} gate (a human answers them, which produces a
 * new spec version).
 */
public final class RequirementsAgent extends LlmAgent {

  public static final String NAME = "RequirementsAgent";

  public RequirementsAgent(AgentKit kit) {
    super(kit, NAME, ArtifactTypes.REQUIREMENTS_SPEC, 8_000);
  }

  @Override
  protected Map<String, String> inputs(StageContext ctx) {
    JsonNode requirement = JSON.readTree(content(ctx, ArtifactTypes.REQUIREMENT));
    String answers =
        ctx.artifact(ArtifactTypes.ANSWERS).map(a -> a.contentJson()).orElse("None yet.");
    return Map.of(
        "requirement", requirement.path("text").asString(""),
        "answers", answers,
        "mode", ctx.run().mode().name());
  }

  @Override
  protected String summary(JsonNode spec) {
    long blocking = 0;
    for (JsonNode a : spec.path("ambiguities")) {
      if ("BLOCKING".equals(a.path("severity").asString(""))) {
        blocking++;
      }
    }
    return size(spec, "functional")
        + " functional and "
        + size(spec, "nonFunctional")
        + " non-functional requirements, "
        + size(spec, "acceptanceCriteria")
        + " acceptance criteria, "
        + blocking
        + " blocking question(s); classified "
        + spec.path("classification").asString("");
  }
}
