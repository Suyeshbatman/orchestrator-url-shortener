package com.acme.orchestrator.agents.sdlc;

import static com.acme.orchestrator.agents.sdlc.AgentHarness.SCHEMAS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.tools.TempRepo;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.agents.tools.WorkspaceManager;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.PolicyLoader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ValidationAgentTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BUILD = "java target/FakeBuild.java";
  private static final String BROKEN_BUILD = "java target/FakeBuild.java fail";

  /** The stage's policies: only the fake builds may run. */
  private static final PolicyEngine COMMANDS =
      PolicyEngine.of(
          new PolicyLoader()
              .parse(
                  "policies:\n  - id: CMD-001\n    on: command\n    allow: [\""
                      + BUILD
                      + "\", \""
                      + BROKEN_BUILD
                      + "\"]\n    effect_outside: DENY\n",
                  """
                  levels:
                    L0_SUGGEST: { description: s }
                    L1_SUPERVISED: { description: s }
                    L2_TRUSTED: { description: s }
                  invariants:
                    - publish requires human approval
                    - DENY policies are never overridable
                    - approvals must come from a human principal with role APPROVER
                  """));

  @TempDir Path tmp;
  private Workspace workspace;
  private AgentHarness h;

  @BeforeEach
  void setUp() throws Exception {
    workspace =
        new WorkspaceManager(TempRepo.create(tmp), tmp.resolve("ws"), "main").create("run-1");
    Path target = Files.createDirectories(workspace.root().resolve("target")); // git-ignored
    try (InputStream in = getClass().getResourceAsStream("/fake-build/FakeBuild.java")) {
      Files.write(target.resolve("FakeBuild.java"), in.readAllBytes());
    }
    h = new AgentHarness(RunMode.GREENFIELD);
  }

  private JsonNode validate(String command) {
    ValidationAgent agent =
        new ValidationAgent(
            PolicyEngine.load(Path.of("..", "config")),
            new ValidationAgent.Settings(
                command,
                "shortener-service",
                Set.of("com.acme.shortener.config"),
                Duration.ofMinutes(2)));
    AgentResult result =
        agent.execute(
            h.context(
                "validation",
                List.of(),
                ToolRegistry.builder().register(Workspace.class, workspace).build(),
                COMMANDS,
                Optional.empty()));
    String content = ((AgentResult.Success) result).artifacts().getFirst().contentJson();
    assertThat(SCHEMAS.validate(ArtifactTypes.VALIDATION_REPORT, content)).isEmpty();
    last = ((AgentResult.Success) result).summary();
    stored = h.input("validation", ArtifactTypes.VALIDATION_REPORT, content);
    return JSON.readTree(content);
  }

  private String last;
  private Artifact stored;

  private Map<String, Boolean> gates() {
    GateRegistry registry = GateRegistry.standard(SCHEMAS);
    GateContext context =
        new GateContext(
            h.run,
            AgentHarness.STANDARD.stage("validation").orElseThrow(),
            GateContext.Phase.EXIT,
            List.of(stored),
            type -> h.artifacts.latestOfType("run-1", type));
    Map<String, Boolean> results = new java.util.LinkedHashMap<>();
    for (GateRef gate : AgentHarness.STANDARD.stage("validation").orElseThrow().exitGates()) {
      results.put(gate.toString(), registry.evaluate(gate, context).passed());
    }
    return results;
  }

  @Test
  void theReportReflectsTheBuildTestsCoverageAndPolicy() {
    JsonNode report = validate(BUILD);

    assertThat(report.path("build").path("status").asString()).isEqualTo("SUCCESS");
    assertThat(report.path("build").path("summary").asString()).contains("BUILD SUCCESS");
    JsonNode tests = report.path("tests");
    assertThat(tests.path("total").asInt()).isEqualTo(5); // surefire + failsafe
    assertThat(tests.path("failed").asInt()).isEqualTo(1);
    assertThat(tests.path("skipped").asInt()).isEqualTo(1);
    assertThat(tests.path("failures").get(0).path("test").asString())
        .isEqualTo("com.acme.shortener.qr.QrTest.rejectsHugeSizes");
    assertThat(tests.path("failures").get(0).path("message").asString())
        .isEqualTo("expected 400 but was 500");
    assertThat(report.path("coverage").path("line").asDouble()).isEqualTo(0.9); // config excluded
    assertThat(report.path("policy").path("decision").asString()).isEqualTo("ALLOW");
    assertThat(report.path("diff").path("filesChanged").asInt()).isZero(); // target/ is ignored
    assertThat(last)
        .isEqualTo("build SUCCESS, 5 tests (1 failed, 0 errors), line coverage 0.90, policy ALLOW");

    assertThat(gates())
        .containsEntry("build.green", true)
        .containsEntry("tests.green", false)
        .containsEntry("coverage.min:0.80", true)
        .containsEntry("policy.noDeny", true);
  }

  @Test
  void aFailedBuildIsReportedWithTheEndOfItsLogAndNoStaleReports() throws Exception {
    validate(BUILD); // leaves reports behind
    JsonNode report = validate(BROKEN_BUILD);

    assertThat(report.path("build").path("status").asString()).isEqualTo("FAILURE");
    assertThat(report.path("build").path("summary").asString()).contains("';' expected");
    assertThat(report.path("tests").path("total").asInt()).isZero(); // the old reports were cleared
    assertThat(report.path("coverage").path("line").asDouble()).isZero();
    assertThat(gates()).containsEntry("build.green", false).containsEntry("tests.green", false);
  }

  @Test
  void aDeniedChangeBecomesAFailedGateSoTheWorkLoopsBack() throws Exception {
    Path leaky = workspace.root().resolve("shortener-service/src/main/java/Config.java");
    Files.createDirectories(leaky.getParent());
    Files.writeString(leaky, "class Config { String password = \"hunter2hunter2\"; }\n");

    JsonNode report = validate(BUILD);

    assertThat(report.path("policy").path("decision").asString()).isEqualTo("DENY");
    assertThat(report.path("policy").path("matchedPolicies").get(0).path("id").asString())
        .isEqualTo("SEC-001-no-secrets");
    assertThat(report.path("diff").path("paths").toString()).contains("Config.java");
    assertThat(gates()).containsEntry("policy.noDeny", false);
  }

  @Test
  void theValidationCommandMustBeAllowlisted() {
    ValidationAgent agent =
        new ValidationAgent(
            PolicyEngine.load(Path.of("..", "config")),
            new ValidationAgent.Settings(
                "rm -rf .", "shortener-service", Set.of(), Duration.ofMinutes(1)));

    assertThatThrownBy(() ->
            agent.execute(
                h.context(
                    "validation",
                    List.of(),
                    ToolRegistry.builder().register(Workspace.class, workspace).build(),
                    COMMANDS,
                    Optional.empty())))
        .hasMessageContaining("not allowed (DENY)");
  }

  @Test
  void reportXmlWithADoctypeIsRejected() throws Exception {
    Path reports = Files.createDirectories(tmp.resolve("m/target/surefire-reports"));
    Files.writeString(
        reports.resolve("TEST-x.xml"),
        "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<testsuite tests=\"1\">&x;</testsuite>");

    assertThatThrownBy(() -> BuildReports.tests(tmp.resolve("m")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot parse test report TEST-x.xml");
  }

  @Test
  void noCoverageReportMeansZeroCoverage() {
    assertThat(BuildReports.lineCoverage(tmp, Set.of())).isZero();
    assertThat(BuildReports.tests(tmp)).isEqualTo(BuildReports.Tests.NONE);
  }
}
