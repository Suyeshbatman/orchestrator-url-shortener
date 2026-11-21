package com.acme.orchestrator.core.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.GateContext.Phase;
import com.acme.orchestrator.core.engine.GateResult;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StandardGatesTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final WorkflowDefinition WORKFLOW =
      new WorkflowLoader()
          .parse(
              """
              workflow: t
              budgets: { maxWallClock: 1m, maxLlmCalls: 1, maxTokens: 1 }
              stages:
                - id: s
                  agent: A
              """,
              "t.yml");
  private static final Run RUN =
      Run.create("run-1", WORKFLOW, RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED);

  private final GateRegistry registry = GateRegistry.standard(SchemaValidator.STRUCTURE_ONLY);
  private final Map<String, Artifact> stored = new HashMap<>();
  private final List<Artifact> produced = new ArrayList<>();
  private int versions;

  private static Artifact artifact(String type, int version, String json) {
    return Artifact.create(
        "run-1:s:" + type + ":v" + version,
        "run-1",
        "s",
        type,
        version,
        json,
        ProducedBy.deterministic("test"),
        List.of(),
        "",
        CLOCK);
  }

  /** An artifact already in the run (an upstream stage made it). */
  private void stored(String type, String json) {
    stored.put(type, artifact(type, ++versions, json));
  }

  /** An artifact the attempt under test produced. */
  private void produced(String type, String json) {
    produced.add(artifact(type, ++versions, json));
  }

  private GateResult eval(String gate) {
    return eval(gate, Phase.EXIT);
  }

  private GateResult eval(String gate, Phase phase) {
    GateContext context =
        new GateContext(
            RUN,
            WORKFLOW.stage("s").orElseThrow(),
            phase,
            produced,
            type -> Optional.ofNullable(stored.get(type)));
    return registry.evaluate(GateRef.parse(gate), context);
  }

  private static GateResult failed(String... reasons) {
    return GateResult.fail(reasons);
  }

  // ---- input.nonEmpty -------------------------------------------------------------------------

  @Test
  void inputNonEmpty() {
    assertThat(eval("input.nonEmpty", Phase.ENTRY)).isEqualTo(failed("no requirement artifact"));

    stored("requirement", "{\"text\":\"   \"}");
    assertThat(eval("input.nonEmpty", Phase.ENTRY))
        .isEqualTo(failed("the requirement text is empty"));

    stored("requirement", "{\"text\":\" Add QR codes \"}");
    assertThat(eval("input.nonEmpty", Phase.ENTRY))
        .isEqualTo(GateResult.pass("requirement has 12 characters"));
  }

  // ---- artifact.schemaValid -------------------------------------------------------------------

  @Test
  void schemaValidRequiresTheAttemptToHaveProducedTheType() {
    stored("task_graph", "{}"); // an older version doesn't count at exit
    assertThat(eval("artifact.schemaValid:task_graph"))
        .isEqualTo(failed("this attempt produced no task_graph"));
    assertThat(eval("artifact.schemaValid:task_graph", Phase.ENTRY).passed()).isTrue();
    assertThat(eval("artifact.schemaValid:design_doc", Phase.ENTRY))
        .isEqualTo(failed("no design_doc artifact"));

    produced("task_graph", "{\"tasks\":[]}");
    assertThat(eval("artifact.schemaValid:task_graph"))
        .isEqualTo(GateResult.pass("task_graph v2 matches the task_graph schema"));
  }

  @Test
  void schemaValidRejectsContentThatIsNotAJsonObject() {
    produced("task_graph", "[1, 2]");
    assertThat(eval("artifact.schemaValid:task_graph"))
        .isEqualTo(failed("task_graph v1 must be a JSON object"));

    produced("task_graph", "{\"tasks\": ");
    assertThat(eval("artifact.schemaValid:task_graph").reasons())
        .singleElement()
        .asString()
        .startsWith("task_graph v2 is not valid JSON: ");
  }

  @Test
  void schemaValidReportsTheValidatorsProblems() {
    SchemaValidator validator =
        new SchemaValidator() {
          @Override
          public boolean supports(String artifactType) {
            return true;
          }

          @Override
          public List<String> validate(String artifactType, String contentJson) {
            return List.of("$.tasks: required", "$.title: must be a string");
          }
        };
    produced("task_graph", "{}");
    GateContext context =
        new GateContext(
            RUN, WORKFLOW.stage("s").orElseThrow(), Phase.EXIT, produced, type -> Optional.empty());

    assertThat(
            GateRegistry.standard(validator)
                .evaluate(GateRef.parse("artifact.schemaValid:task_graph"), context))
        .isEqualTo(
            failed("task_graph v1: $.tasks: required", "task_graph v1: $.title: must be a string"));
  }

  // ---- spec.noBlockingQuestions ---------------------------------------------------------------

  @Test
  void noBlockingQuestions() {
    produced(
        "requirements_spec",
        """
        {"ambiguities": [
          {"id": "A1", "question": "Who counts as an abuser?", "severity": "BLOCKING"},
          {"id": "A2", "question": "Log blocked attempts?", "severity": "NONBLOCKING"}]}
        """);
    assertThat(eval("spec.noBlockingQuestions"))
        .isEqualTo(failed("1 blocking question(s) need an answer", "A1: Who counts as an abuser?"));

    produced(
        "requirements_spec",
        "{\"ambiguities\": [{\"id\": \"A2\", \"severity\": \"NONBLOCKING\"}]}");
    assertThat(eval("spec.noBlockingQuestions"))
        .isEqualTo(GateResult.pass("no blocking questions (1 non-blocking)"));

    produced("requirements_spec", "{}");
    assertThat(eval("spec.noBlockingQuestions").passed()).isTrue();

    produced("requirements_spec", "{\"ambiguities\": \"none\"}");
    assertThat(eval("spec.noBlockingQuestions"))
        .isEqualTo(failed("requirements_spec v4.ambiguities must be a list"));
  }

  // ---- taskGraph.acyclic ----------------------------------------------------------------------

  @Test
  void taskGraphAcyclicPassesForADag() {
    produced(
        "task_graph",
        """
        {"tasks": [
          {"id": "T1", "dependsOn": []},
          {"id": "T2", "dependsOn": ["T1"]},
          {"id": "T3", "dependsOn": ["T1", "T2"]}]}
        """);

    assertThat(eval("taskGraph.acyclic")).isEqualTo(GateResult.pass("3 tasks, no cycles"));
  }

  @Test
  void taskGraphAcyclicNamesTheCycle() {
    produced(
        "task_graph",
        """
        {"tasks": [
          {"id": "T1", "dependsOn": ["T3"]},
          {"id": "T2", "dependsOn": ["T1"]},
          {"id": "T3", "dependsOn": ["T2"]}]}
        """);

    assertThat(eval("taskGraph.acyclic").reasons())
        .singleElement()
        .asString()
        .startsWith("cycle detected: ")
        .contains("T1", "T2", "T3");
  }

  @Test
  void taskGraphAcyclicRejectsBrokenReferences() {
    produced(
        "task_graph",
        """
        {"tasks": [
          {"id": "T1", "dependsOn": ["T1"]},
          {"id": "T2", "dependsOn": ["T9"]}]}
        """);
    assertThat(eval("taskGraph.acyclic"))
        .isEqualTo(failed("task T1 depends on itself", "task T2 depends on unknown task T9"));

    produced("task_graph", "{\"tasks\": [{\"id\": \"T1\"}, {\"id\": \"T1\"}]}");
    assertThat(eval("taskGraph.acyclic"))
        .isEqualTo(failed("task_graph v2 has duplicate task id T1"));

    produced("task_graph", "{\"tasks\": [{\"title\": \"no id\"}]}");
    assertThat(eval("taskGraph.acyclic")).isEqualTo(failed("task_graph v3.tasks[0] has no id"));

    produced("task_graph", "{\"tasks\": []}");
    assertThat(eval("taskGraph.acyclic")).isEqualTo(failed("task_graph v4 has no tasks"));

    produced("task_graph", "{\"tasks\": [{\"id\": \"T1\", \"dependsOn\": [7]}]}");
    assertThat(eval("taskGraph.acyclic"))
        .isEqualTo(failed("task T1.dependsOn must hold non-empty strings"));
  }

  // ---- taskGraph.everyRequirementCovered ------------------------------------------------------

  private static final String SPEC =
      """
      {"functional": [{"id": "FR-1"}, {"id": "FR-2"}], "nonFunctional": [{"id": "NFR-1"}]}
      """;

  @Test
  void everyRequirementCoveredPasses() {
    stored("requirements_spec", SPEC);
    produced(
        "task_graph",
        """
        {"tasks": [
          {"id": "T1", "requirementIds": ["FR-1", "NFR-1"]},
          {"id": "T2", "requirementIds": ["FR-2"]}]}
        """);

    assertThat(eval("taskGraph.everyRequirementCovered"))
        .isEqualTo(GateResult.pass("all 3 requirements are covered"));
  }

  @Test
  void everyRequirementCoveredNamesGapsAndUnknownIds() {
    stored("requirements_spec", SPEC);
    produced(
        "task_graph", "{\"tasks\": [{\"id\": \"T1\", \"requirementIds\": [\"FR-1\", \"FR-7\"]}]}");

    assertThat(eval("taskGraph.everyRequirementCovered"))
        .isEqualTo(
            failed(
                "task T1 refers to unknown requirement FR-7",
                "FR-2 is not covered by any task",
                "NFR-1 is not covered by any task"));
  }

  @Test
  void everyRequirementCoveredNeedsIdentifiedRequirements() {
    produced("task_graph", "{\"tasks\": [{\"id\": \"T1\"}]}");
    assertThat(eval("taskGraph.everyRequirementCovered"))
        .isEqualTo(failed("no requirements_spec artifact"));

    stored("requirements_spec", "{\"functional\": []}");
    assertThat(eval("taskGraph.everyRequirementCovered"))
        .isEqualTo(failed("requirements_spec v2 lists no requirements"));

    stored("requirements_spec", "{\"functional\": [\"show a QR code\"]}");
    assertThat(eval("taskGraph.everyRequirementCovered"))
        .isEqualTo(failed("requirements_spec v3.functional[0] has no id"));
  }

  // ---- design.apiContractPresent --------------------------------------------------------------

  @Test
  void apiContractPresent() {
    produced("design_doc", "{\"apiChanges\": \"paths:\\n  /api/v1/links/{code}/qr: ...\"}");
    assertThat(eval("design.apiContractPresent"))
        .isEqualTo(GateResult.pass("design_doc v1 has an API contract"));

    produced("design_doc", "{\"apiChanges\": {\"paths\": {}}}");
    assertThat(eval("design.apiContractPresent").passed()).isTrue();

    for (String empty : List.of("{}", "{\"apiChanges\": \" \"}", "{\"apiChanges\": {}}")) {
      produced("design_doc", empty);
      assertThat(eval("design.apiContractPresent").passed()).as(empty).isFalse();
    }
    assertThat(eval("design.apiContractPresent").reasons())
        .singleElement()
        .asString()
        .startsWith("design_doc v5.apiChanges is missing or empty");
  }

  // ---- validation_report gates ----------------------------------------------------------------

  @Test
  void buildGreen() {
    produced("validation_report", "{\"build\": {\"status\": \"SUCCESS\"}}");
    assertThat(eval("build.green")).isEqualTo(GateResult.pass("build succeeded"));

    produced(
        "validation_report",
        "{\"build\": {\"status\": \"FAILURE\", \"summary\": \"LinkService.java:[12] ';' expected\"}}");
    assertThat(eval("build.green"))
        .isEqualTo(failed("build status is FAILURE", "LinkService.java:[12] ';' expected"));

    produced("validation_report", "{\"build\": {}}");
    assertThat(eval("build.green")).isEqualTo(failed("build status is missing"));

    produced("validation_report", "{}");
    assertThat(eval("build.green")).isEqualTo(failed("validation_report.build is missing"));
  }

  @Test
  void testsGreen() {
    produced(
        "validation_report",
        "{\"tests\": {\"total\": 12, \"failed\": 0, \"errors\": 0, \"skipped\": 1}}");
    assertThat(eval("tests.green")).isEqualTo(GateResult.pass("12 tests passed (1 skipped)"));

    produced(
        "validation_report",
        """
        {"tests": {"total": 12, "failed": 1, "errors": 1, "failures": [
          {"test": "LinkApiIT.expiredLinkReturns410", "message": "expected 410 but was 302"},
          {"test": "QrControllerTest.size", "message": "NullPointerException"}]}}
        """);
    assertThat(eval("tests.green"))
        .isEqualTo(
            failed(
                "1 failed and 1 errored of 12 tests",
                "LinkApiIT.expiredLinkReturns410: expected 410 but was 302",
                "QrControllerTest.size: NullPointerException"));

    produced("validation_report", "{\"tests\": {\"total\": 0, \"failed\": 0, \"errors\": 0}}");
    assertThat(eval("tests.green")).isEqualTo(failed("no tests ran"));

    produced("validation_report", "{\"tests\": {\"total\": 3, \"failed\": \"0\", \"errors\": 0}}");
    assertThat(eval("tests.green"))
        .isEqualTo(failed("validation_report.tests.failed must be a whole number"));
  }

  @Test
  void testsGreenListsAtMostTenFailures() {
    String failures =
        String.join(
            ",",
            IntStream.rangeClosed(1, 13)
                .mapToObj(i -> "{\"test\": \"T" + i + "\", \"message\": \"boom\"}")
                .toList());
    produced(
        "validation_report",
        "{\"tests\": {\"total\": 20, \"failed\": 13, \"errors\": 0, \"failures\": ["
            + failures
            + "]}}");

    List<String> reasons = eval("tests.green").reasons();
    assertThat(reasons).hasSize(12).endsWith("… and 3 more");
    assertThat(reasons.get(1)).isEqualTo("T1: boom");
  }

  @Test
  void coverageMin() {
    produced("validation_report", "{\"coverage\": {\"line\": 0.83}}");
    assertThat(eval("coverage.min:0.80")).isEqualTo(GateResult.pass("line coverage 0.83 >= 0.8"));

    produced("validation_report", "{\"coverage\": {\"line\": 0.80}}");
    assertThat(eval("coverage.min:0.80").passed()).isTrue(); // the boundary passes

    produced("validation_report", "{\"coverage\": {\"line\": 0.7999}}");
    assertThat(eval("coverage.min:0.80")).isEqualTo(failed("line coverage 0.7999 is below 0.8"));

    produced("validation_report", "{\"coverage\": {\"line\": 83}}");
    assertThat(eval("coverage.min:0.80"))
        .isEqualTo(failed("validation_report.coverage.line must be a fraction between 0 and 1"));

    produced("validation_report", "{\"coverage\": {}}");
    assertThat(eval("coverage.min:0.80").passed()).isFalse();
  }

  @Test
  void policyNoDeny() {
    produced("validation_report", "{\"policy\": {\"decision\": \"ALLOW\"}}");
    assertThat(eval("policy.noDeny")).isEqualTo(GateResult.pass("policy decision ALLOW"));

    produced("validation_report", "{\"policy\": {\"decision\": \"REQUIRE_APPROVAL\"}}");
    assertThat(eval("policy.noDeny").passed()).isTrue(); // approval is handled elsewhere

    produced(
        "validation_report",
        """
        {"policy": {"decision": "DENY", "matchedPolicies": [
          {"id": "SEC-001", "decision": "DENY", "reason": "writes a secret to application.yaml"},
          {"id": "CHG-002", "decision": "REQUIRE_APPROVAL", "reason": "touches a migration"}]}}
        """);
    assertThat(eval("policy.noDeny"))
        .isEqualTo(failed("policy decision DENY", "SEC-001: writes a secret to application.yaml"));

    produced("validation_report", "{\"policy\": {\"decision\": \"MAYBE\"}}");
    assertThat(eval("policy.noDeny"))
        .isEqualTo(
            failed("unknown policy decision 'MAYBE' (expected ALLOW, REQUIRE_APPROVAL or DENY)"));
  }

  @Test
  void theNewestReportIsJudgedNotAnOlderOne() {
    stored("validation_report", "{\"build\": {\"status\": \"SUCCESS\"}}");
    assertThat(eval("build.green").passed()).isTrue();

    produced("validation_report", "{\"build\": {\"status\": \"FAILURE\"}}");
    assertThat(eval("build.green").passed()).isFalse();
  }

  // ---- release.checklistComplete --------------------------------------------------------------

  @Test
  void checklistComplete() {
    produced(
        "release_report",
        """
        {"checklist": [
          {"item": "changelog written", "status": "DONE"},
          {"item": "migration reviewed", "status": "not_applicable"}]}
        """);
    assertThat(eval("release.checklistComplete"))
        .isEqualTo(GateResult.pass("all 2 checklist items are done"));

    produced(
        "release_report",
        """
        {"checklist": [
          {"item": "changelog written", "status": "DONE"},
          {"item": "rollback plan", "status": "PENDING"},
          {"item": "load test"}]}
        """);
    assertThat(eval("release.checklistComplete"))
        .isEqualTo(
            failed(
                "2 checklist item(s) are not done",
                "rollback plan: PENDING",
                "load test: no status"));

    produced("release_report", "{\"checklist\": []}");
    assertThat(eval("release.checklistComplete"))
        .isEqualTo(failed("the release checklist is empty"));
  }
}
