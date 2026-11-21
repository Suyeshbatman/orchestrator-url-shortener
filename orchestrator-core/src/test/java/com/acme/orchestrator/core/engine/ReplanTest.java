package com.acme.orchestrator.core.engine;

import static com.acme.orchestrator.core.engine.Command.ApprovalDecision.APPROVER;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.checkpoint.InMemoryCheckpointManager;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.ProposedAction;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReplanTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Actor ALICE = Actor.human("alice");
  private static final WorkflowDefinition STANDARD =
      new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));

  /** What each stage of the standard workflow reads (its produced type is the stage id). */
  private static final Map<String, List<String>> READS =
      Map.ofEntries(
          Map.entry("requirements", List.of(ArtifactTypes.REQUIREMENT)),
          Map.entry("decomposition", List.of("requirements")),
          Map.entry("impact_analysis", List.of("decomposition")),
          Map.entry("design", List.of("decomposition")),
          Map.entry("implementation", List.of("design")),
          Map.entry("test_authoring", List.of("design")),
          Map.entry("docs_draft", List.of("design")),
          Map.entry("validation", List.of("implementation", "test_authoring")),
          Map.entry("docs_finalize", List.of("validation", "docs_draft")),
          Map.entry("release_readiness", List.of("docs_finalize")),
          Map.entry("publish", List.of("release_readiness")));

  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final InMemoryEventStore events = new InMemoryEventStore(clock);
  private final InMemoryArtifactStore artifacts = new InMemoryArtifactStore(clock);
  private final InMemoryCheckpointManager workspace = new InMemoryCheckpointManager();
  private Coordinator coordinator;

  @AfterEach
  void close() {
    if (coordinator != null) {
      coordinator.close();
    }
  }

  /** Reads its inputs and produces content derived from them, so new inputs → new content. */
  private static Agent stage() {
    return ctx -> {
      StringBuilder from = new StringBuilder();
      for (String type : READS.get(ctx.stageKey())) {
        from.append(ctx.requireArtifact(type).contentHash(), 0, 8).append(' ');
      }
      return AgentResult.success(
          ctx.stageKey(),
          ArtifactOutput.of(
              ctx.stageKey(), "{\"from\":\"" + from.toString().strip() + "\"}", "test"));
    };
  }

  private void storeRequirement(String text) {
    artifacts.save(
        new ArtifactDraft(
            "run-1",
            ArtifactTypes.INPUT_STAGE,
            ArtifactTypes.REQUIREMENT,
            "{\"text\":\"" + text + "\"}",
            ProducedBy.deterministic("human:alice"),
            List.of(),
            "as given"));
  }

  private void start(WorkflowDefinition wf, Map<String, Agent> agents) {
    coordinator =
        Coordinator.start(
            wf,
            Run.create("run-1", wf, RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED),
            new Coordinator.Services(
                    events,
                    artifacts,
                    AgentRegistry.of(agents),
                    GateEvaluator.PASS_ALL,
                    ToolRegistry.EMPTY,
                    PolicyEngine.load(Path.of("..", "config")))
                .withCheckpoints(workspace));
  }

  private RunState awaitWaiting(String stage) throws Exception {
    return coordinator.await(
        s ->
            s.stages().containsKey(stage)
                && s.stages().get(stage).status() == StageStatus.WAITING_APPROVAL,
        WAIT);
  }

  private void approve(String stage) {
    coordinator.submit(
        new Command.ApprovalDecision(stage, ALICE, APPROVER, Decision.APPROVE, "ok"));
  }

  private List<Event> ofType(EventType type) {
    return events.events("run-1").stream().filter(e -> e.type() == type).toList();
  }

  private long count(EventType type, String stage) {
    return ofType(type).stream().filter(e -> e.stageId().filter(stage::equals).isPresent()).count();
  }

  @Test
  void amendingTheRequirementAfterDesignApprovalReplansWithGovernance() throws Exception {
    storeRequirement("QR codes for links");
    CountDownLatch implementationStarted = new CountDownLatch(1);
    AtomicInteger implementationCalls = new AtomicInteger();
    Map<String, Agent> agents = new HashMap<>();
    for (String agent :
        List.of(
            "RequirementsAgent",
            "DecompositionAgent",
            "ImpactAnalysisAgent",
            "DesignAgent",
            "TestAuthoringAgent",
            "DocsAgent",
            "ValidationAgent",
            "ReleaseAgent",
            "git.mergeToMain")) {
      agents.put(agent, stage());
    }
    agents.put(
        "ImplementationAgent",
        ctx -> {
          ctx.authorize(
              new ProposedAction.FileWrite("shortener-service/src/main/java/Qr.java", "v"));
          workspace.write("shortener-service/src/main/java/Qr.java", "v" + ctx.attempt());
          if (implementationCalls.incrementAndGet() == 1) {
            implementationStarted.countDown();
            new CountDownLatch(1).await(); // still coding when the requirement changes
          }
          return stage().execute(ctx);
        });
    start(STANDARD, agents);

    awaitWaiting("requirements");
    approve("requirements");
    awaitWaiting("design");
    approve("design");
    implementationStarted.await(5, TimeUnit.SECONDS);
    coordinator.await(
        s ->
            s.statuses().get("test_authoring") == StageStatus.SUCCEEDED
                && s.statuses().get("docs_draft") == StageStatus.SUCCEEDED,
        WAIT);
    Artifact specV1 = artifacts.latestOfType("run-1", "requirements").orElseThrow();

    coordinator.submit(new Command.AmendRequirement("QR codes and expiring links", ALICE));
    awaitWaiting("requirements"); // the approval was voided: it must be given again

    // what the re-plan recorded
    Event replan = ofType(EventType.REPLAN_TRIGGERED).getFirst();
    assertThat(replan.actor()).isEqualTo(ALICE);
    assertThat(replan.payload())
        .containsEntry("trigger", "amendment")
        .containsEntry("amendment", "QR codes and expiring links")
        .containsEntry(
            "invalidated",
            List.of(
                "requirements",
                "decomposition",
                "impact_analysis",
                "design",
                "implementation",
                "test_authoring",
                "docs_draft"))
        .containsEntry("voidedApprovals", List.of("requirements", "design"))
        .containsEntry("restoredCheckpoint", "cp-1");
    assertThat(replan.payload().get("before").toString()).contains("run-1:input:requirement:v1");
    assertThat(replan.payload().get("after").toString()).contains("run-1:input:requirement:v2");
    assertThat(ofType(EventType.STAGE_INVALIDATED))
        .filteredOn(e -> e.payload().containsKey("approvalVoided"))
        .extracting(e -> e.stageId().orElseThrow())
        .containsExactly("requirements", "design");
    assertThat(workspace.restored()).hasSize(1); // back to before implementation

    // re-approval is required at every checkpoint again
    approve("requirements");
    awaitWaiting("design");
    assertThat(count(EventType.APPROVAL_REQUESTED, "design")).isEqualTo(2);
    approve("design");
    awaitWaiting("release_readiness");
    approve("release_readiness");
    awaitWaiting("publish");
    approve("publish");
    RunState end = coordinator.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);

    assertThat(implementationCalls).hasValue(2); // the first attempt was cancelled, its result void
    assertThat(artifacts.versions("run-1", "implementation", "implementation")).hasSize(1);
    assertThat(end.stages().get("design").loopCount()).isEqualTo(1);

    // requirements history stays in the lineage: spec v2 ← requirement v2 ← requirement v1
    Artifact specV2 = artifacts.latestOfType("run-1", "requirements").orElseThrow();
    assertThat(specV2.version()).isEqualTo(2);
    assertThat(specV2.contentHash()).isNotEqualTo(specV1.contentHash());
    assertThat(artifacts.lineage(specV2.id()).ancestors())
        .extracting(Artifact::id)
        .contains("run-1:input:requirement:v2", "run-1:input:requirement:v1");
    assertThat(artifacts.lineage(specV1.id()).ancestors())
        .extracting(Artifact::id)
        .containsExactly("run-1:input:requirement:v1");
    assertThat(new AuditVerifier(events).verify("run-1").valid()).isTrue();
  }

  @Test
  void anAmendmentBeforeAnythingReadTheRequirementReRunsTheFirstStages() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    WorkflowDefinition wf =
        new WorkflowLoader()
            .parse(
                """
                workflow: t
                budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
                stages:
                  - id: a
                    agent: A
                  - id: b
                    agent: A
                    dependsOn: [a]
                """,
                "t.yml");
    start(
        wf,
        Map.of(
            "A",
            ctx -> {
              if (calls.incrementAndGet() == 1) {
                release.await(); // running, has not produced anything yet
              }
              return AgentResult.success("ok", ArtifactOutput.of(ctx.stageKey(), "{}", ""));
            }));
    coordinator.await(s -> s.statuses().get("a") == StageStatus.RUNNING, WAIT);

    coordinator.submit(new Command.AmendRequirement("first version", ALICE));
    coordinator.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);

    assertThat(ofType(EventType.REPLAN_TRIGGERED).getFirst().payload())
        .containsEntry("changed", List.of("a"))
        .containsEntry("invalidated", List.of("a"))
        .doesNotContainKey("before");
    assertThat(
            artifacts.latestOfType("run-1", ArtifactTypes.REQUIREMENT).orElseThrow().contentJson())
        .isEqualTo("{\"text\":\"first version\"}");
    assertThat(
            events.events("run-1").stream()
                .filter(e -> e.type() == EventType.STAGE_INVALIDATED)
                .findFirst()
                .orElseThrow()
                .payload())
        .containsEntry("feedback", "requirement amended by human:alice: first version");
    assertThat(calls).hasValue(3); // a (cancelled), a again, b
  }

  @Test
  void anAmendmentToAFinishedRunIsIgnored() throws Exception {
    WorkflowDefinition wf =
        new WorkflowLoader()
            .parse(
                """
                workflow: t
                budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
                stages:
                  - id: a
                    agent: A
                """,
                "t.yml");
    start(wf, Map.of("A", ctx -> AgentResult.success("ok")));
    coordinator.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);

    coordinator.submit(new Command.AmendRequirement("too late", ALICE));
    coordinator.submit(new Command.Wake());
    coordinator.await(s -> true, WAIT);

    assertThat(ofType(EventType.REPLAN_TRIGGERED)).isEmpty();
    assertThat(artifacts.byRun("run-1")).isEmpty();
  }

  @Test
  void aRejectedStageWhoseDownstreamHasNotStartedInvalidatesNothing() throws Exception {
    WorkflowDefinition wf =
        new WorkflowLoader()
            .parse(
                """
                workflow: t
                budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
                stages:
                  - id: a
                    agent: A
                    approval: { required: true, reason: r }
                    retry: { maxAttempts: 2 }
                  - id: b
                    agent: A
                    dependsOn: [a]
                """,
                "t.yml");
    start(
        wf,
        Map.of(
            "A",
            ctx ->
                AgentResult.success(
                    "ok", ArtifactOutput.of(ctx.stageKey(), "{\"v\":" + ctx.attempt() + "}", ""))));
    awaitWaiting("a");
    coordinator.submit(new Command.ApprovalDecision("a", ALICE, APPROVER, Decision.REJECT, "redo"));
    coordinator.await(
        s ->
            s.stages().get("a").attempt() == 2
                && s.stages().get("a").status() == StageStatus.WAITING_APPROVAL,
        WAIT);
    approve("a");
    coordinator.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);

    // a's output changed (v1 → v2), but nothing had consumed v1: no re-plan needed
    assertThat(artifacts.versions("run-1", "a", "a")).hasSize(2);
    assertThat(ofType(EventType.REPLAN_TRIGGERED)).isEmpty();
    assertThat(ofType(EventType.STAGE_INVALIDATED)).isEmpty();
  }
}
