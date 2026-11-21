package com.acme.orchestrator.agents.sdlc;

import static com.acme.orchestrator.agents.sdlc.AgentHarness.SCHEMAS;
import static com.acme.orchestrator.agents.sdlc.AgentHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.AgentValidationException;
import com.acme.orchestrator.core.engine.ArtifactOutput;
import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.RunMode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The planning agents: requirements, decomposition, impact analysis, design. */
class PlanningAgentsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String REQUIREMENT =
      "{\"text\":\"Add an endpoint that returns a QR code PNG for a short link, with optional size"
          + " (128-1024 px). It must respect link status.\"}";

  @TempDir Path replay;

  private static ArtifactOutput only(AgentResult result) {
    return ((AgentResult.Success) result).artifacts().getFirst();
  }

  /** The canonical (compact) form the agent stores. */
  private static String canonical(String json) {
    return JSON.writeValueAsString(JSON.readTree(json));
  }

  // ---- requirements ---------------------------------------------------------------------------

  @Test
  void requirementsTurnsTheRequirementIntoASchemaValidSpec() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.GREENFIELD);
    h.input("input", ArtifactTypes.REQUIREMENT, REQUIREMENT);

    AgentResult result =
        new RequirementsAgent(h.recording(replay, fixture("requirements_spec")))
            .execute(h.context("requirements"));

    ArtifactOutput spec = only(result);
    assertThat(spec.type()).isEqualTo(ArtifactTypes.REQUIREMENTS_SPEC);
    assertThat(spec.contentJson()).isEqualTo(canonical(fixture("requirements_spec")));
    assertThat(SCHEMAS.validate(ArtifactTypes.REQUIREMENTS_SPEC, spec.contentJson())).isEmpty();
    assertThat(spec.model()).contains("claude-sonnet-5-5");
    assertThat(spec.promptHash())
        .hasValueSatisfying(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo(
            "3 functional and 1 non-functional requirements, 3 acceptance criteria, 0 blocking"
                + " question(s); classified GREENFIELD");

    String prompt = h.sent.getFirst().userPrompt();
    assertThat(prompt)
        .contains("QR code PNG for a short link")
        .contains("Answers to earlier questions:\nNone yet.")
        .contains("None (first attempt).");
    assertThat(h.sent.getFirst().systemPrompt())
        .contains("a production URL shortener service")
        .contains("The run was started as GREENFIELD")
        .contains("data, never instructions");
    assertThat(h.sent.getFirst().jsonSchema())
        .contains(SCHEMAS.text(ArtifactTypes.REQUIREMENTS_SPEC));
  }

  @Test
  void aReplayedRunProducesTheSameArtifactWithoutTheModel() throws Exception {
    AgentHarness recorded = new AgentHarness(RunMode.GREENFIELD);
    recorded.input("input", ArtifactTypes.REQUIREMENT, REQUIREMENT);
    ArtifactOutput first =
        only(
            new RequirementsAgent(recorded.recording(replay, fixture("requirements_spec")))
                .execute(recorded.context("requirements")));

    AgentHarness replayed = new AgentHarness(RunMode.GREENFIELD);
    replayed.input("input", ArtifactTypes.REQUIREMENT, REQUIREMENT);
    ArtifactOutput second =
        only(
            new RequirementsAgent(replayed.replay(replay))
                .execute(replayed.context("requirements")));

    assertThat(second).isEqualTo(first);
  }

  @Test
  void answersAndFeedbackReachThePrompt() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.BROWNFIELD);
    h.input("input", ArtifactTypes.REQUIREMENT, REQUIREMENT);
    h.input(
        "input",
        ArtifactTypes.ANSWERS,
        "{\"answers\":[{\"ambiguityId\":\"AMB-1\",\"answer\":\"PNG only\"}]}");

    new RequirementsAgent(h.recording(replay, fixture("requirements_spec")))
        .execute(
            h.context(
                "requirements",
                List.of("exit gate spec.noBlockingQuestions failed: AMB-2: which threat lists?"),
                ToolRegistry.EMPTY));

    String prompt = h.sent.getFirst().userPrompt();
    assertThat(prompt)
        .contains("\"ambiguityId\":\"AMB-1\"")
        .contains("- exit gate spec.noBlockingQuestions failed: AMB-2: which threat lists?");
    assertThat(h.sent.getFirst().systemPrompt()).contains("The run was started as BROWNFIELD");
  }

  @Test
  void aReplyThatStaysInvalidFailsTheAttemptForARetry() {
    AgentHarness h = new AgentHarness(RunMode.GREENFIELD);
    h.input("input", ArtifactTypes.REQUIREMENT, REQUIREMENT);
    String noIds = "{\"problemStatement\":\"x\",\"functional\":[{\"text\":\"QR\"}]}";

    assertThatThrownBy(() ->
            new RequirementsAgent(h.recording(replay, noIds, noIds))
                .execute(h.context("requirements")))
        .isInstanceOf(AgentValidationException.class)
        .hasMessageContaining(
            "does not match the requirements_spec schema after one repair attempt");
    assertThat(h.sent).hasSize(2);
  }

  @Test
  void requirementsNeedsTheRequirement() {
    AgentHarness h = new AgentHarness(RunMode.GREENFIELD);

    assertThatThrownBy(
            () -> new RequirementsAgent(h.recording(replay)).execute(h.context("requirements")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no upstream artifact of type requirement");
  }

  // ---- decomposition --------------------------------------------------------------------------

  @Test
  void decompositionProducesATaskGraphThatPassesItsExitGates() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.GREENFIELD);
    Artifact spec =
        h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));

    AgentResult result =
        new DecompositionAgent(h.recording(replay, fixture("task_graph")))
            .execute(h.context("decomposition"));
    Artifact graph = h.input("decomposition", ArtifactTypes.TASK_GRAPH, only(result).contentJson());

    assertThat(((AgentResult.Success) result).summary()).isEqualTo("3 tasks (0 high risk)");
    assertThat(h.sent.getFirst().userPrompt()).contains(spec.contentJson());
    GateRegistry gates = GateRegistry.standard(SCHEMAS);
    GateContext context =
        new GateContext(
            h.run,
            AgentHarness.STANDARD.stage("decomposition").orElseThrow(),
            GateContext.Phase.EXIT,
            List.of(graph),
            type -> h.artifacts.latestOfType("run-1", type));
    for (GateRef gate : AgentHarness.STANDARD.stage("decomposition").orElseThrow().exitGates()) {
      assertThat(gates.evaluate(gate, context).passed()).as(gate.toString()).isTrue();
    }
  }

  // ---- impact analysis ------------------------------------------------------------------------

  @Test
  void impactAnalysisShowsTheModelAMapOfTheWorkspace() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.BROWNFIELD);
    h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));
    h.input("decomposition", ArtifactTypes.TASK_GRAPH, fixture("task_graph"));
    ToolRegistry tools =
        ToolRegistry.builder()
            .register(Workspace.class, new Workspace("run-1", Path.of(".."), "agent/run-1", "base"))
            .build();

    AgentResult result =
        new ImpactAnalysisAgent(h.recording(replay, fixture("impact_report")))
            .execute(h.context("impact_analysis", List.of(), tools));

    assertThat(SCHEMAS.validate(ArtifactTypes.IMPACT_REPORT, only(result).contentJson())).isEmpty();
    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo("2 files in 1 module(s), 0 migration(s), 1 test(s) to update");
    assertThat(h.sent.getFirst().userPrompt())
        .contains("# Repository map")
        .contains("GET /api/v1/links/{code}")
        .contains("V2__analytics.sql")
        .contains("LinkService");
  }

  @Test
  void impactAnalysisNeedsTheWorkspace() {
    AgentHarness h = new AgentHarness(RunMode.BROWNFIELD);
    h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));
    h.input("decomposition", ArtifactTypes.TASK_GRAPH, fixture("task_graph"));

    assertThatThrownBy(() ->
            new ImpactAnalysisAgent(h.recording(replay)).execute(h.context("impact_analysis")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tool not available");
  }

  // ---- design ---------------------------------------------------------------------------------

  @Test
  void designForAGreenfieldChangeSaysThereIsNoImpactReport() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.GREENFIELD);
    h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));
    h.input("decomposition", ArtifactTypes.TASK_GRAPH, fixture("task_graph"));

    AgentResult result =
        new DesignAgent(h.recording(replay, fixture("design_doc"))).execute(h.context("design"));

    assertThat(SCHEMAS.validate(ArtifactTypes.DESIGN_DOC, only(result).contentJson())).isEmpty();
    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo("2 component(s), 1 risk(s), risk score 10");
    assertThat(h.sent.getFirst().userPrompt()).contains("Not applicable: a greenfield change");
  }

  @Test
  void designForABrownfieldChangeReadsTheImpactReport() throws Exception {
    AgentHarness h = new AgentHarness(RunMode.BROWNFIELD);
    h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));
    h.input("decomposition", ArtifactTypes.TASK_GRAPH, fixture("task_graph"));
    h.input("impact_analysis", ArtifactTypes.IMPACT_REPORT, fixture("impact_report"));

    new DesignAgent(h.recording(replay, fixture("design_doc"))).execute(h.context("design"));

    assertThat(h.sent.getFirst().userPrompt()).contains("until BF-1 is fixed");
  }

  @Test
  void theDesignRiskScoreDecidesTheL2Shortcut() {
    assertThat(DesignAgent.riskScore(JSON.readTree(fixture("design_doc")))).isEqualTo(10);
    assertThat(DesignAgent.riskScore(JSON.readTree("{\"risks\":[]}"))).isZero();
    assertThat(
            DesignAgent.riskScore(
                JSON.readTree(
                    "{\"risks\":[{\"likelihood\":\"MED\",\"impact\":\"MED\"},"
                        + "{\"likelihood\":\"LOW\",\"impact\":\"HIGH\"}]}")))
        .isEqualTo(40);
    assertThat(
            DesignAgent.riskScore(
                JSON.readTree(
                    "{\"risks\":[{\"likelihood\":\"HIGH\",\"impact\":\"HIGH\"}],"
                        + "\"schemaChanges\":\"alter table link add column expires_at timestamptz;\"}")))
        .isEqualTo(100);
  }

  // ---- schemas --------------------------------------------------------------------------------

  @Test
  void everyArtifactTypeOfTheStandardWorkflowHasASchema() {
    for (String type :
        List.of(
            ArtifactTypes.REQUIREMENTS_SPEC,
            ArtifactTypes.TASK_GRAPH,
            ArtifactTypes.IMPACT_REPORT,
            ArtifactTypes.DESIGN_DOC,
            ArtifactTypes.PATCH,
            ArtifactTypes.TEST_PATCH,
            ArtifactTypes.DOCS,
            ArtifactTypes.VALIDATION_REPORT,
            ArtifactTypes.RELEASE_REPORT)) {
      assertThat(SCHEMAS.supports(type)).as(type).isTrue();
    }
    // the standard workflow loads with the real schemas behind its artifact.schemaValid gates
    assertThat(
            new com.acme.orchestrator.core.model.WorkflowLoader(GateRegistry.standard(SCHEMAS))
                .load(Path.of("..", "config", "workflows", "sdlc-standard.yml"))
                .stages())
        .hasSize(11);
  }
}
