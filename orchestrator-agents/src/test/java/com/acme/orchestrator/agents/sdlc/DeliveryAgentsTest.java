package com.acme.orchestrator.agents.sdlc;

import static com.acme.orchestrator.agents.sdlc.AgentHarness.SCHEMAS;
import static com.acme.orchestrator.agents.sdlc.AgentHarness.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.tools.PolicyDeniedException;
import com.acme.orchestrator.agents.tools.TempRepo;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.agents.tools.WorkspaceManager;
import com.acme.orchestrator.core.engine.AgentRegistry;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.AgentValidationException;
import com.acme.orchestrator.core.engine.GateContext;
import com.acme.orchestrator.core.engine.StageContext;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The delivery agents, writing into a real Git workspace under the real policies. */
class DeliveryAgentsTest {

  static final PolicyEngine POLICIES = PolicyEngine.load(Path.of("..", "config"));
  static final String LINK = "shortener-service/src/main/java/com/acme/shortener/Link.java";
  static final String QR =
      "shortener-service/src/main/java/com/acme/shortener/qr/QrController.java";

  @TempDir Path tmp;
  Path replay;
  Workspace workspace;
  ToolRegistry tools;
  AgentHarness h;

  @BeforeEach
  void setUp() throws Exception {
    replay = Files.createDirectories(tmp.resolve("replay"));
    workspace =
        new WorkspaceManager(TempRepo.create(tmp), tmp.resolve("ws"), "main").create("run-1");
    tools = ToolRegistry.builder().register(Workspace.class, workspace).build();
    h = new AgentHarness(RunMode.GREENFIELD);
    h.input("requirements", ArtifactTypes.REQUIREMENTS_SPEC, fixture("requirements_spec"));
    h.input(
        "decomposition",
        ArtifactTypes.TASK_GRAPH,
        fixture("task_graph").replace("qr/QrEncoder.java", "Link.java")); // a file that exists
    h.input("design", ArtifactTypes.DESIGN_DOC, fixture("design_doc"));
  }

  private StageContext ctx(String stage) {
    return h.context(stage, List.of(), tools, POLICIES, Optional.empty());
  }

  private String read(String path) throws Exception {
    return Files.readString(workspace.root().resolve(path));
  }

  // ---- implementation -------------------------------------------------------------------------

  @Test
  void implementationWritesThePatchIntoTheWorkspace() throws Exception {
    AgentResult result =
        new ImplementationAgent(h.recording(replay, fixture("patch")))
            .execute(ctx("implementation"));

    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo("QR endpoint and encoder (2 file(s) written, 0 deleted)");
    assertThat(read(QR)).contains("class QrController");
    assertThat(read(LINK)).contains("boolean active");
    String prompt = h.sent.getFirst().userPrompt();
    assertThat(prompt)
        .contains("### " + LINK) // the current content of the files the tasks name
        .contains("public record Link(String code) {}")
        .contains("None yet (first implementation).")
        .contains("None (greenfield).");
    assertThat(h.sent.getFirst().systemPrompt())
        .contains("Implement every task in the task graph.")
        .contains("Never write secrets");
  }

  @Test
  void aPatchTouchingAForbiddenPathIsRefused() {
    String patch =
        "{\"summary\":\"bump a dependency\",\"files\":[{\"path\":\"shortener-service/pom.xml\","
            + "\"newContent\":\"<project/>\"}]}";

    assertThatThrownBy(() ->
            new ImplementationAgent(h.recording(replay, patch)).execute(ctx("implementation")))
        .isInstanceOf(PolicyDeniedException.class)
        .hasMessageContaining("SEC-002");
    assertThat(workspace.root().resolve("shortener-service/pom.xml")).doesNotExist();
  }

  @Test
  void theMinimalPatchFallbackMustChangeExactlyOneFile() throws Exception {
    StageContext fallback =
        h.context(
            "implementation",
            List.of("still failing"),
            tools,
            POLICIES,
            Optional.of("minimal-patch"));

    assertThatThrownBy(
            () -> new ImplementationAgent(h.recording(replay, fixture("patch"))).execute(fallback))
        .isInstanceOf(AgentValidationException.class)
        .hasMessage("the minimal-patch fallback must change exactly one file, not 2");
    assertThat(h.sent.getFirst().systemPrompt()).contains("change exactly ONE file");
    assertThat(workspace.root().resolve(QR)).doesNotExist(); // nothing applied

    String onePatch =
        "{\"summary\":\"minimal\",\"files\":[{\"path\":\""
            + QR
            + "\",\"newContent\":\"class Q {}\"}]}";
    new ImplementationAgent(h.recording(replay, onePatch)).execute(fallback);
    assertThat(read(QR)).isEqualTo("class Q {}");
  }

  @Test
  void aLoopBackShowsTheImplementationTheValidationReport() {
    h.input("validation", ArtifactTypes.VALIDATION_REPORT, "{\"tests\":{\"failed\":2}}");

    new ImplementationAgent(h.recording(replay, fixture("patch"))).execute(ctx("implementation"));

    assertThat(h.sent.getFirst().userPrompt()).contains("{\"tests\":{\"failed\":2}}");
  }

  // ---- test authoring -------------------------------------------------------------------------

  @Test
  void testsAreWrittenFromTheContractNeverFromTheCode() throws Exception {
    h.input("implementation", ArtifactTypes.PATCH, fixture("patch")); // exists, must not be read

    AgentResult result =
        new TestAuthoringAgent(h.recording(replay, fixture("test_patch")))
            .execute(ctx("test_authoring"));

    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo("1 test file(s) covering AC-1, AC-2, AC-3");
    assertThat(read("shortener-service/src/test/java/com/acme/shortener/qr/QrApiIT.java"))
        .contains("class QrApiIT");
    assertThat(h.sent.getFirst().userPrompt())
        .contains("qr.QrController") // from the design
        .doesNotContain("QR endpoint and encoder"); // the patch
    assertThat(h.sent.getFirst().systemPrompt()).contains("Never mock the database");
  }

  @Test
  void testFilesOutsideTheTestTreeFailTheSchema() {
    String wrongPlace =
        "{\"summary\":\"t\",\"files\":[{\"path\":\""
            + QR
            + "\",\"newContent\":\"x\",\"covers\":[\"AC-1\"]}]}";

    assertThatThrownBy(() ->
            new TestAuthoringAgent(h.recording(replay, wrongPlace, wrongPlace))
                .execute(ctx("test_authoring")))
        .isInstanceOf(AgentValidationException.class);
  }

  // ---- docs -----------------------------------------------------------------------------------

  @Test
  void docsAreDraftedFromTheDesignThenFinalizedWithTheValidationResults() throws Exception {
    new DocsAgent(h.recording(replay, fixture("docs"))).execute(ctx("docs_draft"));
    assertThat(read("docs/api/qr-codes.md")).contains("# QR codes");
    assertThat(h.sent.getFirst().systemPrompt()).contains("This is the draft pass.");
    assertThat(h.sent.getFirst().userPrompt()).contains("Not available yet");

    h.input("docs_draft", ArtifactTypes.DOCS, fixture("docs"));
    h.input("validation", ArtifactTypes.VALIDATION_REPORT, "{\"coverage\":{\"line\":0.86}}");
    new DocsAgent(h.recording(replay, fixture("docs"))).execute(ctx("docs_finalize"));
    assertThat(h.sent.get(1).systemPrompt()).contains("This is the final pass.");
    assertThat(h.sent.get(1).userPrompt())
        .contains("0.86")
        .contains("API note for the QR endpoint");
  }

  // ---- release --------------------------------------------------------------------------------

  @Test
  void theReleaseReportPassesTheChecklistGateAndSeesTheDiff() throws Exception {
    Files.writeString(
        workspace.root().resolve(QR.replace("qr/QrController", "Qr")), "class Qr {}\n");
    h.input("validation", ArtifactTypes.VALIDATION_REPORT, "{\"coverage\":{\"line\":0.86}}");

    AgentResult result =
        new ReleaseAgent(h.recording(replay, fixture("release_report")))
            .execute(ctx("release_readiness"));
    Artifact report =
        h.input(
            "release_readiness",
            ArtifactTypes.RELEASE_REPORT,
            ((AgentResult.Success) result).artifacts().getFirst().contentJson());

    assertThat(((AgentResult.Success) result).summary())
        .isEqualTo("4 of 4 checklist items done, 0 open issue(s)");
    assertThat(h.sent.getFirst().userPrompt())
        .contains(
            "1 files, 1 lines changed: shortener-service/src/main/java/com/acme/shortener/Qr.java");
    GateContext gate =
        new GateContext(
            h.run,
            AgentHarness.STANDARD.stage("release_readiness").orElseThrow(),
            GateContext.Phase.EXIT,
            List.of(report),
            type -> h.artifacts.latestOfType("run-1", type));
    assertThat(
            GateRegistry.standard(SCHEMAS)
                .evaluate(GateRef.parse("release.checklistComplete"), gate)
                .passed())
        .isTrue();
  }

  // ---- registry -------------------------------------------------------------------------------

  @Test
  void theStandardAgentsCoverTheWorkflowExceptThePublishAction() {
    var agents =
        SdlcAgents.standard(h.replay(replay), POLICIES, ValidationAgent.Settings.SHORTENER);

    assertThat(AgentRegistry.of(agents).missing(AgentHarness.STANDARD))
        .containsExactly("git.mergeToMain");
    assertThat(agents).hasSize(9);
  }
}
