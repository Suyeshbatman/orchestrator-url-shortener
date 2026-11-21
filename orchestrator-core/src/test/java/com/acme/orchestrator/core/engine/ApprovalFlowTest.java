package com.acme.orchestrator.core.engine;

import static com.acme.orchestrator.core.engine.Command.ApprovalDecision.APPROVER;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.model.AutonomyLevel;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApprovalFlowTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Actor ALICE = Actor.human("alice");
  private static final PolicyEngine POLICIES = PolicyEngine.load(Path.of("..", "config"));
  private static final String MIGRATION =
      "shortener-service/src/main/resources/db/migration/V3__link_expiry.sql";

  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final InMemoryEventStore events = new InMemoryEventStore(clock);
  private final InMemoryArtifactStore artifacts = new InMemoryArtifactStore(clock);
  private Coordinator coordinator;

  @AfterEach
  void close() {
    if (coordinator != null) {
      coordinator.close();
    }
  }

  private static WorkflowDefinition workflow(String stagesYaml) {
    return new WorkflowLoader()
        .parse(
            """
            workflow: test
            budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
            stages:
            """
                + stagesYaml,
            "test.yml");
  }

  private void start(WorkflowDefinition wf, AutonomyLevel level, Agent agent) {
    coordinator =
        Coordinator.start(
            wf,
            Run.create("run-1", wf, RunMode.GREENFIELD, level),
            new Coordinator.Services(
                events,
                artifacts,
                AgentRegistry.of(Map.of("A", agent, "git.mergeToMain", agent)),
                GateEvaluator.PASS_ALL,
                ToolRegistry.EMPTY,
                POLICIES));
  }

  private static Agent produces(String type) {
    return ctx -> AgentResult.success("made " + type, ArtifactOutput.of(type, "{}", "test"));
  }

  private RunState awaitStatus(RunStatus status) throws Exception {
    return coordinator.await(s -> s.run().status() == status, WAIT);
  }

  private List<Event> ofType(EventType type) {
    return events.events("run-1").stream().filter(e -> e.type() == type).toList();
  }

  private void decide(String stage, Actor who, String role, Decision decision, String comment) {
    coordinator.submit(new ApprovalDecision(stage, who, role, decision, comment));
  }

  // ---- who may approve ------------------------------------------------------------------------

  @Test
  void onlyHumansWithTheApproverRoleCountAndRefusalsAreAudited() throws Exception {
    start(
        workflow(
            "  - id: a\n    agent: A\n    approval: { required: true, reason: \"sign-off\" }\n"),
        AutonomyLevel.L1_SUPERVISED,
        produces("x"));
    awaitStatus(RunStatus.AWAITING_HUMAN);

    decide("a", Actor.human("bob"), "DEVELOPER", Decision.APPROVE, "lgtm");
    decide("a", Actor.agent("ReleaseAgent"), APPROVER, Decision.APPROVE, "lgtm");
    decide("a", Actor.SYSTEM, APPROVER, Decision.REJECT, "");
    decide("a", ALICE, APPROVER, Decision.APPROVE, "ok");
    awaitStatus(RunStatus.COMPLETED);

    List<Event> refusals = ofType(EventType.POLICY_EVALUATED);
    assertThat(refusals).hasSize(3);
    assertThat(refusals)
        .extracting(Event::actor)
        .containsExactly(Actor.human("bob"), Actor.agent("ReleaseAgent"), Actor.SYSTEM);
    assertThat(refusals.getFirst().payload())
        .containsEntry("action", "approval approve")
        .containsEntry("effect", "DENY");
    assertThat(refusals.getFirst().payload().get("matchedPolicies").toString())
        .contains(Coordinator.HUMAN_APPROVER_POLICY)
        .contains("human:bob has role 'DEVELOPER', not APPROVER");
    assertThat(refusals.get(1).payload().get("matchedPolicies").toString())
        .contains("only a human can approve, not agent:ReleaseAgent");
    assertThat(ofType(EventType.APPROVAL_GRANTED))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.actor()).isEqualTo(ALICE);
          assertThat(e.payload()).containsEntry("role", APPROVER);
        });
  }

  // ---- reject → re-run ------------------------------------------------------------------------

  @Test
  void aRejectionReRunsTheStageWithTheCommentAsFeedback() throws Exception {
    List<List<String>> seen = new CopyOnWriteArrayList<>();
    Agent agent =
        ctx -> {
          seen.add(ctx.feedback());
          return AgentResult.success(
              "spec v" + ctx.attempt(), ArtifactOutput.of("spec", "{}", "test"));
        };
    start(
        workflow(
            """
              - id: a
                agent: A
                approval: { required: true, reason: "Scope sign-off" }
                retry: { maxAttempts: 2 }
              - id: b
                agent: A
                dependsOn: [a]
            """),
        AutonomyLevel.L1_SUPERVISED,
        agent);

    awaitStatus(RunStatus.AWAITING_HUMAN);
    decide("a", ALICE, APPROVER, Decision.REJECT, "add rate limits to the scope");
    coordinator.await(
        s -> s.run().status() == RunStatus.AWAITING_HUMAN && s.stages().get("a").attempt() == 2,
        WAIT);
    decide("a", ALICE, APPROVER, Decision.APPROVE, "better");
    awaitStatus(RunStatus.COMPLETED);

    assertThat(seen.get(0)).isEmpty();
    assertThat(seen.get(1)).containsExactly("rejected: add rate limits to the scope");
    assertThat(seen.get(2)).isEmpty(); // stage b
    assertThat(artifacts.versions("run-1", "a", "spec")).hasSize(2);
    assertThat(ofType(EventType.APPROVAL_REQUESTED)).hasSize(2);
    assertThat(new AuditVerifier(events).verify("run-1").valid()).isTrue();
  }

  @Test
  void rejectionsCountTowardTheRetryBudget() throws Exception {
    start(
        workflow(
            """
              - id: a
                agent: A
                approval: { required: true, reason: "Scope sign-off" }
                retry: { maxAttempts: 2 }
            """),
        AutonomyLevel.L1_SUPERVISED,
        produces("spec"));

    awaitStatus(RunStatus.AWAITING_HUMAN);
    decide("a", ALICE, APPROVER, Decision.REJECT, "no");
    coordinator.await(
        s -> s.run().status() == RunStatus.AWAITING_HUMAN && s.stages().get("a").attempt() == 2,
        WAIT);
    decide("a", ALICE, APPROVER, Decision.REJECT, "still no");

    RunState end = awaitStatus(RunStatus.FAILED);
    assertThat(end.statuses()).containsEntry("a", StageStatus.FAILED);
    assertThat(ofType(EventType.STAGE_FAILED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry(
                    "reason",
                    "rejected by human:alice and no attempts are left (2 of 2 used): still no"));
  }

  // ---- policy-triggered approvals -------------------------------------------------------------

  @Test
  void aPolicyRequireApprovalMakesTheStageWaitWithAFullSummary() throws Exception {
    Agent agent =
        ctx -> {
          ctx.authorize(new ProposedAction.FileWrite(MIGRATION, "alter table link ..."));
          ctx.authorize(
              new ProposedAction.Diff(
                  "--- a/" + MIGRATION + "\n+++ b/" + MIGRATION + "\n@@ -0,0 +1,1 @@\n+alter\n",
                  1,
                  1));
          return AgentResult.success("added expiry", ArtifactOutput.of("patch", "{}", "test"));
        };
    start(workflow("  - id: impl\n    agent: A\n"), AutonomyLevel.L1_SUPERVISED, agent);

    RunState waiting = awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(waiting.statuses()).containsEntry("impl", StageStatus.WAITING_APPROVAL);
    assertThat(ofType(EventType.POLICY_EVALUATED))
        .extracting(e -> e.payload().get("action"))
        .containsExactly("file_write " + MIGRATION, "diff 1 files, 1 lines");

    Map<String, Object> summary = ofType(EventType.APPROVAL_REQUESTED).getFirst().payload();
    assertThat(summary)
        .containsEntry(
            "reason",
            "policy: file_write "
                + MIGRATION
                + " needs approval (CHG-001-schema-migration); policy: diff 1 files, 1 lines"
                + " needs approval (CHG-001-schema-migration)")
        .containsEntry("summary", "added expiry")
        .containsEntry("riskScore", 21L) // CHG-001 (20) + size 1
        .containsEntry("diffStats", Map.of("filesChanged", 1L, "linesChanged", 1L))
        .containsEntry("artifacts", List.of("run-1:impl:patch:v1"));
    assertThat(summary.get("matchedPolicies").toString()).contains("CHG-001-schema-migration");
    assertThat(summary.get("artifactRefs").toString())
        .contains("run-1:impl:patch:v1", "contentHash");

    decide("impl", ALICE, APPROVER, Decision.APPROVE, "migration reviewed");
    awaitStatus(RunStatus.COMPLETED);
  }

  @Test
  void aPolicyDenyFailsTheStageAndStoresNothing() throws Exception {
    Agent agent =
        ctx -> {
          ctx.authorize(new ProposedAction.FileWrite("README.md", "# hi"));
          return AgentResult.success("wrote readme", ArtifactOutput.of("patch", "{}", "test"));
        };
    start(workflow("  - id: impl\n    agent: A\n"), AutonomyLevel.L1_SUPERVISED, agent);

    awaitStatus(RunStatus.FAILED);
    assertThat(ofType(EventType.STAGE_FAILED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry(
                    "reason", "policy denied: file_write README.md (SEC-002-path-jail)"));
    assertThat(artifacts.byRun("run-1")).isEmpty();
    assertThat(ofType(EventType.POLICY_EVALUATED))
        .singleElement()
        .satisfies(e -> assertThat(e.payload()).containsEntry("effect", "DENY"));
  }

  @Test
  void policyChecksOfAFailedAttemptAreStillAudited() throws Exception {
    Agent agent =
        ctx -> {
          ctx.authorize(new ProposedAction.Command("rm -rf /"));
          return AgentResult.failure("gave up");
        };
    start(workflow("  - id: impl\n    agent: A\n"), AutonomyLevel.L1_SUPERVISED, agent);

    awaitStatus(RunStatus.FAILED);
    assertThat(ofType(EventType.POLICY_EVALUATED))
        .singleElement()
        .satisfies(e -> assertThat(e.payload()).containsEntry("action", "command 'rm -rf /'"));
  }

  // ---- L2 design relaxation -------------------------------------------------------------------

  private static final String DESIGN =
      "  - id: design\n    agent: A\n    approval: { required: true, reason: \"Architecture\" }\n";

  private static Agent designWithRisk(int risk) {
    return ctx -> {
      ctx.reportRisk(risk);
      return AgentResult.success("design", ArtifactOutput.of("design_doc", "{}", "test"));
    };
  }

  @Test
  void atL2ALowRiskDesignSkipsItsApproval() throws Exception {
    start(workflow(DESIGN), AutonomyLevel.L2_TRUSTED, designWithRisk(10));

    awaitStatus(RunStatus.COMPLETED);
    assertThat(ofType(EventType.APPROVAL_REQUESTED)).isEmpty();
  }

  @Test
  void atL2ARiskyDesignStillWaitsAndShowsItsRisk() throws Exception {
    start(workflow(DESIGN), AutonomyLevel.L2_TRUSTED, designWithRisk(45));

    awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(ofType(EventType.APPROVAL_REQUESTED).getFirst().payload())
        .containsEntry("riskScore", 45L)
        .containsEntry("reason", "Architecture");
  }

  @Test
  void atL1EvenALowRiskDesignWaits() throws Exception {
    start(workflow(DESIGN), AutonomyLevel.L1_SUPERVISED, designWithRisk(10));

    awaitStatus(RunStatus.AWAITING_HUMAN);
  }

  // ---- timeout --------------------------------------------------------------------------------

  @Test
  void anUnansweredApprovalHaltsTheRunAfterItsTimeout() throws Exception {
    start(
        workflow(
            "  - id: a\n    agent: A\n"
                + "    approval: { required: true, reason: \"sign-off\", timeout: 200ms }\n"),
        AutonomyLevel.L1_SUPERVISED,
        produces("x"));

    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.HALTED);
    assertThat(ofType(EventType.SAFE_STOP_REQUESTED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry("reason", "approval of a was not given within PT0.2S")
                .containsEntry("stageId", "a"));
    assertThat(ofType(EventType.RUN_HALTED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry("reason", "approval of a was not given within PT0.2S"));
    assertThat(ofType(EventType.APPROVAL_REQUESTED).getFirst().payload())
        .containsEntry("timeout", "PT0.2S");

    // resuming re-runs the stage, which asks again
    coordinator.submit(new Command.Resume(ALICE));
    coordinator.await(
        s ->
            s.statuses().get("a") == StageStatus.WAITING_APPROVAL
                && s.stages().get("a").attempt() == 1
                && ofType(EventType.APPROVAL_REQUESTED).size() == 2,
        WAIT);
  }

  @Test
  void withoutATimeoutAnApprovalWaitsUntilDecided() throws Exception {
    start(
        workflow(
            "  - id: a\n    agent: A\n    approval: { required: true, reason: \"sign-off\" }\n"),
        AutonomyLevel.L1_SUPERVISED,
        produces("x"));

    awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(ofType(EventType.APPROVAL_REQUESTED).getFirst().payload())
        .doesNotContainKey("timeout");
    decide("a", ALICE, APPROVER, Decision.APPROVE, "");
    awaitStatus(RunStatus.COMPLETED);
  }

  // ---- publish: approved before it runs -------------------------------------------------------

  private static final String PUBLISH =
      """
        - id: release
          agent: A
        - id: publish
          action: git.mergeToMain
          dependsOn: [release]
          approval: { required: true, reason: "Merge to main" }
      """;

  @Test
  void aPublishIsApprovedBeforeItRunsAndRunsOnlyOnce() throws Exception {
    java.util.concurrent.atomic.AtomicInteger merges =
        new java.util.concurrent.atomic.AtomicInteger();
    Agent agent =
        ctx -> {
          if (ctx.stageKey().equals("publish")) {
            merges.incrementAndGet();
          }
          return AgentResult.success("done", ArtifactOutput.of(ctx.stageKey(), "{}", "test"));
        };
    start(workflow(PUBLISH), AutonomyLevel.L2_TRUSTED, agent);

    RunState waiting = awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(waiting.statuses()).containsEntry("publish", StageStatus.WAITING_APPROVAL);
    assertThat(waiting.stages().get("publish").attempt()).isZero(); // nothing ran yet
    assertThat(merges).hasValue(0);
    assertThat(ofType(EventType.APPROVAL_REQUESTED).getFirst().payload())
        .containsEntry("before", true)
        .containsEntry("reason", "Merge to main")
        .containsEntry("summary", "git.mergeToMain runs once this is approved");

    decide("publish", ALICE, APPROVER, Decision.APPROVE, "ship it");
    awaitStatus(RunStatus.COMPLETED);

    assertThat(merges).hasValue(1);
    assertThat(ofType(EventType.APPROVAL_REQUESTED)).hasSize(1); // not asked again afterwards
    List<EventType> order =
        events.events("run-1").stream()
            .filter(e -> e.stageId().filter("publish"::equals).isPresent())
            .map(Event::type)
            .toList();
    assertThat(order.indexOf(EventType.APPROVAL_GRANTED))
        .isLessThan(order.indexOf(EventType.AGENT_INVOKED));
  }

  @Test
  void aRejectedPublishNeverRuns() throws Exception {
    java.util.concurrent.atomic.AtomicInteger merges =
        new java.util.concurrent.atomic.AtomicInteger();
    Agent agent =
        ctx -> {
          if (ctx.stageKey().equals("publish")) {
            merges.incrementAndGet();
          }
          return AgentResult.success("done");
        };
    start(
        workflow(PUBLISH.replace("    approval:", "    retry: { maxAttempts: 3 }\n    approval:")),
        AutonomyLevel.L1_SUPERVISED,
        agent);
    awaitStatus(RunStatus.AWAITING_HUMAN);

    decide("publish", ALICE, APPROVER, Decision.REJECT, "no-go: wait for the freeze to end");
    awaitStatus(RunStatus.FAILED);

    assertThat(merges).hasValue(0); // even with attempts to spare
    assertThat(ofType(EventType.STAGE_FAILED).getFirst().payload())
        .containsEntry(
            "reason", "rejected by human:alice before it ran: no-go: wait for the freeze to end");
  }
}
