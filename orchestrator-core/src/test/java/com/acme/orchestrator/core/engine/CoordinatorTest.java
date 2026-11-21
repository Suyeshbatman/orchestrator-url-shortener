package com.acme.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.gate.SchemaValidator;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CoordinatorTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Actor ALICE = Actor.human("alice");

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

  // ---- fixtures -----------------------------------------------------------------------------

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

  private static Run run(WorkflowDefinition wf, RunMode mode) {
    return Run.create("run-1", wf, mode, AutonomyLevel.L1_SUPERVISED);
  }

  /** Reads each type in {@code reads}, then produces one artifact of {@code produces}. */
  private static Agent producer(String produces, String... reads) {
    return ctx -> {
      for (String type : reads) {
        ctx.requireArtifact(type);
      }
      return AgentResult.success(
          "made " + produces,
          ArtifactOutput.of(produces, "{\"by\":\"" + ctx.stageKey() + "\"}", "test"));
    };
  }

  private Coordinator start(WorkflowDefinition wf, RunMode mode, Map<String, Agent> agents) {
    return start(wf, mode, agents, GateEvaluator.PASS_ALL);
  }

  private Coordinator start(
      WorkflowDefinition wf, RunMode mode, Map<String, Agent> agents, GateEvaluator gates) {
    coordinator =
        Coordinator.start(
            wf,
            run(wf, mode),
            new Coordinator.Services(
                events, artifacts, AgentRegistry.of(agents), gates, ToolRegistry.EMPTY));
    return coordinator;
  }

  private RunState awaitStatus(RunStatus status) throws Exception {
    return coordinator.await(s -> s.run().status() == status, WAIT);
  }

  /** "TYPE stage" for every event, e.g. "STAGE_READY a". */
  private List<String> trail() {
    return events.events("run-1").stream()
        .map(e -> e.type() + e.stageId().map(s -> " " + s).orElse(""))
        .toList();
  }

  private long seqOf(EventType type, String stage) {
    return events.events("run-1").stream()
        .filter(e -> e.type() == type && e.stageId().filter(stage::equals).isPresent())
        .mapToLong(Event::seq)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type + " for " + stage));
  }

  // ---- tests --------------------------------------------------------------------------------

  @Test
  void linearChainRunsInOrderAndRecordsLineage() throws Exception {
    WorkflowDefinition wf =
        workflow(
            """
              - id: a
                agent: A
              - id: b
                agent: B
                dependsOn: [a]
              - id: c
                agent: C
                dependsOn: [b]
            """);
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of(
            "A", producer("spec"), "B", producer("plan", "spec"), "C", producer("code", "plan")));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    List<String> perStage =
        List.of(
            "STAGE_READY",
            "STAGE_STARTED",
            "AGENT_INVOKED",
            "ARTIFACT_PRODUCED",
            "STAGE_SUCCEEDED");
    List<String> expected = new java.util.ArrayList<>(List.of("RUN_CREATED"));
    for (String s : List.of("a", "b", "c")) {
      perStage.forEach(t -> expected.add(t + " " + s));
    }
    expected.add("RUN_COMPLETED");
    assertThat(trail()).containsExactlyElementsOf(expected);

    Artifact code = artifacts.latestOfType("run-1", "code").orElseThrow();
    assertThat(artifacts.lineage(code.id()).ancestors())
        .extracting(Artifact::type)
        .containsExactly("spec", "plan");
    assertThat(code.producedBy().agent()).isEqualTo("C");

    assertThat(end).isEqualTo(RunStateProjector.project(events.events("run-1"))); // event-sourced
    assertThat(new AuditVerifier(events).verify("run-1").valid()).isTrue();
  }

  /** The acceptance test for Step 3.4. */
  @Test
  void standardWorkflowRunsParallelBranchesConcurrentlyAndJoinsCorrectly() throws Exception {
    WorkflowDefinition wf =
        new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));
    CyclicBarrier allThreeRunning = new CyclicBarrier(3);
    Map<String, long[]> window = new ConcurrentHashMap<>(); // stage → {start, end} nanos
    Agent parallel =
        ctx -> {
          long start = System.nanoTime();
          // Only returns once all three branches are inside execute() at the same time.
          allThreeRunning.await(5, TimeUnit.SECONDS);
          window.put(ctx.stageKey(), new long[] {start, System.nanoTime()});
          return AgentResult.success(ctx.stageKey(), ArtifactOutput.of(ctx.stageKey(), "{}", ""));
        };
    Agent docs =
        ctx ->
            ctx.stageKey().equals("docs_draft")
                ? parallel.execute(ctx)
                : AgentResult.success("final docs", ArtifactOutput.of("docs", "{}", ""));
    Map<String, Agent> agents = new HashMap<>();
    for (String name :
        List.of(
            "RequirementsAgent",
            "DecompositionAgent",
            "ImpactAnalysisAgent",
            "DesignAgent",
            "ValidationAgent",
            "ReleaseAgent",
            "git.mergeToMain")) {
      agents.put(name, ctx -> AgentResult.success(ctx.stageKey()));
    }
    agents.put("ImplementationAgent", parallel);
    agents.put("TestAuthoringAgent", parallel);
    agents.put("DocsAgent", docs);
    start(wf, RunMode.GREENFIELD, agents);

    // Approve every checkpoint as it comes up (requirements, design, release_readiness, publish).
    RunState state = coordinator.awaitSettled(WAIT);
    while (state.run().status() == RunStatus.AWAITING_HUMAN) {
      state
          .statuses()
          .forEach((stage, status) -> {
            if (status == StageStatus.WAITING_APPROVAL) {
              coordinator.submit(
                  new Command.ApprovalDecision(stage, ALICE, Decision.APPROVE, "ok"));
            }
          });
      long seen = state.lastSeq();
      state =
          coordinator.await(s -> s.lastSeq() > seen && s.run().status() != RunStatus.RUNNING, WAIT);
    }

    assertThat(state.run().status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(state.statuses()).containsEntry("impact_analysis", StageStatus.SKIPPED);

    // Overlap in time: every branch started before any branch finished.
    long latestStart = window.values().stream().mapToLong(w -> w[0]).max().orElseThrow();
    long earliestEnd = window.values().stream().mapToLong(w -> w[1]).min().orElseThrow();
    assertThat(window).containsOnlyKeys("implementation", "test_authoring", "docs_draft");
    assertThat(latestStart).isLessThan(earliestEnd);

    // Joins: validation starts only after both of its dependencies succeeded, and so on.
    assertThat(seqOf(EventType.STAGE_STARTED, "validation"))
        .isGreaterThan(seqOf(EventType.STAGE_SUCCEEDED, "implementation"))
        .isGreaterThan(seqOf(EventType.STAGE_SUCCEEDED, "test_authoring"));
    assertThat(seqOf(EventType.STAGE_STARTED, "docs_finalize"))
        .isGreaterThan(seqOf(EventType.STAGE_SUCCEEDED, "validation"))
        .isGreaterThan(seqOf(EventType.STAGE_SUCCEEDED, "docs_draft"));
    // The three branches are dispatched in one pass, in declaration order.
    assertThat(
            trail().stream()
                .filter(t -> t.startsWith("STAGE_STARTED "))
                .map(t -> t.substring("STAGE_STARTED ".length()))
                .toList())
        .containsSubsequence(
            "design", "implementation", "test_authoring", "docs_draft", "validation");
    assertThat(seqOf(EventType.STAGE_STARTED, "docs_draft"))
        .isLessThan(seqOf(EventType.STAGE_SUCCEEDED, "implementation")); // not one after another
    assertThat(new AuditVerifier(events).verify("run-1").valid()).isTrue();
  }

  @Test
  void falseConditionSkipsTheStageAndStillSatisfiesTheJoin() throws Exception {
    String yaml =
        """
          - id: decomposition
            agent: D
          - id: impact
            agent: I
            dependsOn: [decomposition]
            condition: "run.mode == 'BROWNFIELD'"
          - id: design
            agent: X
            dependsOn: [decomposition, impact]
        """;
    AtomicInteger impactCalls = new AtomicInteger();
    Map<String, Agent> agents =
        Map.of(
            "D", producer("tasks"),
            "I",
                ctx -> {
                  impactCalls.incrementAndGet();
                  return AgentResult.success("impact");
                },
            "X", producer("design"));

    start(workflow(yaml), RunMode.GREENFIELD, agents);
    RunState greenfield = awaitStatus(RunStatus.COMPLETED);

    assertThat(greenfield.statuses())
        .containsEntry("impact", StageStatus.SKIPPED)
        .containsEntry("design", StageStatus.SUCCEEDED);
    assertThat(impactCalls).hasValue(0);
    assertThat(trail()).contains("STAGE_SKIPPED impact").doesNotContain("STAGE_STARTED impact");

    coordinator.close();
    InMemoryEventStore otherEvents = new InMemoryEventStore(clock);
    coordinator =
        Coordinator.start(
            workflow(yaml),
            run(workflow(yaml), RunMode.BROWNFIELD),
            Coordinator.Services.of(
                otherEvents, new InMemoryArtifactStore(clock), AgentRegistry.of(agents)));
    RunState brownfield = awaitStatus(RunStatus.COMPLETED);
    assertThat(brownfield.statuses()).containsEntry("impact", StageStatus.SUCCEEDED);
    assertThat(impactCalls).hasValue(1);
  }

  @Test
  void eventOrderIsDeterministic() throws Exception {
    String yaml =
        """
          - id: a
            agent: A
          - id: b
            agent: B
            dependsOn: [a]
          - id: c
            agent: B
            dependsOn: [a]
          - id: d
            agent: B
            dependsOn: [a]
          - id: e
            agent: A
            dependsOn: [b, c, d]
        """;
    Map<String, Agent> agents = Map.of("A", producer("x"), "B", ctx -> AgentResult.success("b"));
    List<String> headHashes = new java.util.ArrayList<>();
    List<List<String>> dispatchOrders = new java.util.ArrayList<>();

    for (int i = 0; i < 3; i++) {
      InMemoryEventStore log = new InMemoryEventStore(clock);
      try (Coordinator c =
          Coordinator.start(
              workflow(yaml),
              run(workflow(yaml), RunMode.GREENFIELD),
              Coordinator.Services.of(
                  log, new InMemoryArtifactStore(clock), AgentRegistry.of(agents)))) {
        c.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);
        dispatchOrders.add(
            log.events("run-1").stream()
                .filter(e -> e.type() == EventType.STAGE_STARTED)
                .map(e -> e.stageId().orElseThrow())
                .toList());
        // Everything up to the end of the fan-out dispatch pass (READY/STARTED/INVOKED for b, c, d)
        // happens before any parallel result is handled, so it is identical hash for hash.
        headHashes.add(log.events("run-1").get(14).hash());
      }
    }

    assertThat(dispatchOrders)
        .allSatisfy(o -> assertThat(o.subList(0, 4)).containsExactly("a", "b", "c", "d"));
    assertThat(dispatchOrders).allSatisfy(o -> assertThat(o.getLast()).isEqualTo("e"));
    assertThat(headHashes).containsOnly(headHashes.getFirst());
  }

  @Test
  void aFailedStageStopsDispatchingAndFailsTheRunOnceInFlightWorkEnds() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    WorkflowDefinition wf =
        workflow(
            """
              - id: a
                agent: A
              - id: b
                agent: Fails
                dependsOn: [a]
              - id: slow
                agent: Slow
                dependsOn: [a]
              - id: c
                agent: A
                dependsOn: [b]
            """);
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of(
            "A", producer("x"),
            "Fails", ctx -> AgentResult.failure("boom"),
            "Slow",
                ctx -> {
                  release.await();
                  return AgentResult.success("slow done");
                }));

    coordinator.await(s -> s.statuses().get("b") == StageStatus.FAILED, WAIT);
    assertThat(coordinator.state().run().status()).isEqualTo(RunStatus.RUNNING); // slow in flight
    release.countDown();

    RunState end = awaitStatus(RunStatus.FAILED);
    assertThat(end.statuses())
        .containsEntry("b", StageStatus.FAILED)
        .containsEntry("slow", StageStatus.SUCCEEDED)
        .doesNotContainKey("c");
    Event failed = events.head("run-1").orElseThrow();
    assertThat(failed.type()).isEqualTo(EventType.RUN_FAILED);
    assertThat(failed.payload()).containsEntry("stageId", "b");
    assertThat(
            events.events("run-1").stream()
                .filter(e -> e.type() == EventType.STAGE_FAILED)
                .findFirst()
                .orElseThrow()
                .payload())
        .containsEntry("reason", "boom");
  }

  @Test
  void anAgentThatThrowsOrHangsFailsItsStage() throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    WorkflowDefinition wf =
        workflow(
            """
              - id: throws
                agent: Throws
              - id: hangs
                agent: Hangs
                timeout: 200ms
            """);
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of(
            "Throws",
                ctx -> {
                  throw new IllegalStateException("disk full");
                },
            "Hangs",
                ctx -> {
                  never.await();
                  return AgentResult.success("never");
                }));

    awaitStatus(RunStatus.FAILED);
    Map<String, String> reasons = new HashMap<>();
    events.events("run-1").stream()
        .filter(e -> e.type() == EventType.STAGE_FAILED)
        .forEach(
            e -> reasons.put(e.stageId().orElseThrow(), e.payloadString("reason").orElseThrow()));
    assertThat(reasons)
        .containsEntry("throws", "agent threw IllegalStateException: disk full")
        .containsEntry("hangs", "timed out after PT0.2S");
  }

  @Test
  void gatesAreEvaluatedRecordedAndEnforced() throws Exception {
    WorkflowDefinition wf =
        workflow(
            """
              - id: a
                agent: A
                entryGates: [input.nonEmpty]
                exitGates: [quality.ok]
              - id: b
                agent: A
                dependsOn: [a]
                entryGates: [budget.left]
            """);
    AtomicInteger calls = new AtomicInteger();
    GateEvaluator gates =
        (gate, ctx) ->
            switch (gate.name()) {
              case "quality.ok" -> {
                assertThat(ctx.produced()).extracting(Artifact::type).containsExactly("x");
                yield GateResult.pass("looks fine");
              }
              case "budget.left" -> throw new IllegalStateException("meter offline");
              default -> GateResult.pass();
            };
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of(
            "A",
            ctx -> {
              calls.incrementAndGet();
              return AgentResult.success("x", ArtifactOutput.of("x", "{}", ""));
            }),
        gates);

    awaitStatus(RunStatus.FAILED);
    List<Event> gateEvents =
        events.events("run-1").stream().filter(e -> e.type() == EventType.GATE_EVALUATED).toList();
    assertThat(gateEvents)
        .extracting(e ->
            e.payloadString("gate").orElseThrow() + "=" + e.payloadString("passed").orElseThrow())
        .containsExactly("input.nonEmpty=true", "quality.ok=true", "budget.left=false");
    assertThat(gateEvents.get(1).payload()).containsEntry("phase", "EXIT");
    assertThat(calls).hasValue(1); // b's agent never ran: its entry gate failed
    assertThat(coordinator.state().statuses()).containsEntry("b", StageStatus.FAILED);
    assertThat(trail()).doesNotContain("STAGE_STARTED b");
  }

  @Test
  void aFailingExitGateFailsTheStage() throws Exception {
    WorkflowDefinition wf = workflow("  - id: a\n    agent: A\n    exitGates: [tests.green]\n");
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of("A", producer("x")),
        (gate, ctx) -> GateResult.fail("3 tests failed"));

    awaitStatus(RunStatus.FAILED);
    assertThat(events.events("run-1").stream().filter(e -> e.type() == EventType.STAGE_FAILED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry("reason", "exit gate tests.green failed: 3 tests failed"));
  }

  @Test
  void approvalsComeOnlyFromHumansAndRejectionFailsTheStage() throws Exception {
    WorkflowDefinition wf =
        workflow(
            """
              - id: a
                agent: A
                approval: { required: true, reason: "Scope sign-off" }
              - id: b
                agent: A
                dependsOn: [a]
            """);
    start(wf, RunMode.GREENFIELD, Map.of("A", producer("x")));

    RunState waiting = awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(waiting.statuses()).containsEntry("a", StageStatus.WAITING_APPROVAL);
    Event requested = events.head("run-1").orElseThrow();
    assertThat(requested.type()).isEqualTo(EventType.APPROVAL_REQUESTED);
    assertThat(requested.payload()).containsEntry("reason", "Scope sign-off");

    // An agent can't approve, and approving an unknown stage does nothing.
    coordinator.submit(
        new Command.ApprovalDecision("a", Actor.agent("DesignAgent"), Decision.APPROVE, "lgtm"));
    coordinator.submit(new Command.ApprovalDecision("zzz", ALICE, Decision.APPROVE, ""));
    coordinator.submit(new Command.ApprovalDecision("a", ALICE, Decision.REJECT, "scope too big"));

    RunState end = awaitStatus(RunStatus.FAILED);
    assertThat(end.statuses()).containsEntry("a", StageStatus.FAILED).doesNotContainKey("b");
    List<Event> decisions =
        events.events("run-1").stream()
            .filter(e ->
                e.type() == EventType.APPROVAL_GRANTED || e.type() == EventType.APPROVAL_REJECTED)
            .toList();
    assertThat(decisions)
        .singleElement()
        .satisfies(e -> {
          assertThat(e.type()).isEqualTo(EventType.APPROVAL_REJECTED);
          assertThat(e.actor()).isEqualTo(ALICE);
          assertThat(e.payload()).containsEntry("comment", "scope too big");
        });
  }

  @Test
  void haltStopsDispatchIgnoresLateResultsAndResumeRunsTheStageAgain() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch firstCallStarted = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    WorkflowDefinition wf =
        workflow(
            """
              - id: a
                agent: A
              - id: b
                agent: B
                dependsOn: [a]
            """);
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of(
            "A",
            ctx -> {
              if (calls.incrementAndGet() == 1) {
                firstCallStarted.countDown();
                release.await(); // still running when the halt arrives
              }
              return AgentResult.success("a", ArtifactOutput.of("x", "{}", ""));
            },
            "B",
            producer("y", "x")));

    firstCallStarted.await(5, TimeUnit.SECONDS);
    coordinator.submit(new Command.Halt("operator stop", ALICE));
    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.HALTED);
    coordinator.submit(new Command.Halt("again", ALICE)); // no-op

    release.countDown(); // the first attempt's result arrives late: it must be ignored
    coordinator.submit(new Command.Resume(ALICE));

    RunState end = awaitStatus(RunStatus.COMPLETED);
    assertThat(calls).hasValue(2);
    assertThat(artifacts.versions("run-1", "a", "x")).hasSize(1); // the late result stored nothing
    assertThat(end.stages().get("a").attempt()).isEqualTo(1);
    assertThat(trail()).containsOnlyOnce("RUN_HALTED", "RUN_RESUMED");
    Event halt =
        events.events("run-1").stream()
            .filter(e -> e.type() == EventType.RUN_HALTED)
            .findFirst()
            .orElseThrow();
    assertThat(halt.actor()).isEqualTo(ALICE);
    assertThat(halt.payload()).containsEntry("reason", "operator stop");
  }

  @Test
  void amendmentsAreRecordedForReplanning() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    start(
        workflow("  - id: a\n    agent: A\n"),
        RunMode.GREENFIELD,
        Map.of(
            "A",
            ctx -> {
              release.await();
              return AgentResult.success("a");
            }));

    coordinator.submit(new Command.AmendRequirement("also support QR codes", ALICE));
    coordinator.await(s -> s.lastSeq() >= 5, WAIT);
    release.countDown();
    awaitStatus(RunStatus.COMPLETED);

    Event amended =
        events.events("run-1").stream()
            .filter(e -> e.type() == EventType.REPLAN_TRIGGERED)
            .findFirst()
            .orElseThrow();
    assertThat(amended.actor()).isEqualTo(ALICE);
    assertThat(amended.payload()).containsEntry("amendment", "also support QR codes");
  }

  @Test
  void theRealGatesFailAStageWithTheirReasons() throws Exception {
    WorkflowDefinition wf =
        workflow("  - id: v\n    agent: V\n    exitGates: [build.green, coverage.min:0.80]\n");
    Agent validator =
        ctx ->
            AgentResult.success(
                "validated",
                ArtifactOutput.of(
                    "validation_report",
                    "{\"build\":{\"status\":\"SUCCESS\"},\"coverage\":{\"line\":0.5}}",
                    "test"));
    start(
        wf,
        RunMode.GREENFIELD,
        Map.of("V", validator),
        GateRegistry.standard(SchemaValidator.STRUCTURE_ONLY));

    awaitStatus(RunStatus.FAILED);
    assertThat(events.events("run-1").stream().filter(e -> e.type() == EventType.GATE_EVALUATED))
        .extracting(e -> e.payload().get("passed"))
        .containsExactly(true, false);
    assertThat(events.events("run-1").stream().filter(e -> e.type() == EventType.STAGE_FAILED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry(
                    "reason",
                    "exit gate coverage.min:0.80 failed: line coverage 0.5 is below 0.8"));
  }

  @Test
  void theRunIsTiedToThePoliciesItStartedUnder() throws Exception {
    WorkflowDefinition wf = workflow("  - id: a\n    agent: A\n");
    PolicyEngine policies = PolicyEngine.load(Path.of("..", "config"));
    AgentRegistry agents = AgentRegistry.of(Map.of("A", producer("x")));
    coordinator =
        Coordinator.start(
            wf,
            run(wf, RunMode.GREENFIELD),
            new Coordinator.Services(
                events, artifacts, agents, GateEvaluator.PASS_ALL, ToolRegistry.EMPTY, policies));
    awaitStatus(RunStatus.COMPLETED);
    coordinator.close();

    assertThat(events.events("run-1").getFirst().payload())
        .containsEntry("policiesHash", policies.policies().policiesHash())
        .containsEntry("autonomyHash", policies.policies().autonomyHash());

    // continuing under the same files is fine; under edited ones it is refused
    coordinator =
        Coordinator.start(
            wf,
            run(wf, RunMode.GREENFIELD),
            new Coordinator.Services(
                events, artifacts, agents, GateEvaluator.PASS_ALL, ToolRegistry.EMPTY, policies));
    coordinator.close();
    coordinator = null;
    assertThatThrownBy(() ->
            Coordinator.start(
                wf,
                run(wf, RunMode.GREENFIELD),
                new Coordinator.Services(
                    events, artifacts, agents, GateEvaluator.PASS_ALL, ToolRegistry.EMPTY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("started under different policies");
  }

  @Test
  void aPublishStageWaitsForAHumanEvenWithoutAConfiguredApproval() throws Exception {
    WorkflowDefinition wf = workflow("  - id: publish\n    action: git.mergeToMain\n");
    Agent merge = ctx -> AgentResult.success("merged");
    start(wf, RunMode.GREENFIELD, Map.of("git.mergeToMain", merge));

    coordinator.await(s -> s.statuses().get("publish") == StageStatus.WAITING_APPROVAL, WAIT);
    assertThat(
            events.events("run-1").stream().filter(e -> e.type() == EventType.APPROVAL_REQUESTED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload()).containsEntry("reason", "publish requires human approval"));

    coordinator.submit(new Command.ApprovalDecision("publish", ALICE, Decision.APPROVE, "ship it"));
    awaitStatus(RunStatus.COMPLETED);
  }

  @Test
  void startRejectsGatesTheRegistryDoesNotKnow() {
    WorkflowDefinition wf = workflow("  - id: a\n    agent: A\n    exitGates: [build.gren]\n");
    Coordinator.Services services =
        new Coordinator.Services(
            events,
            artifacts,
            AgentRegistry.of(Map.of("A", producer("x"))),
            GateRegistry.standard(SchemaValidator.STRUCTURE_ONLY),
            ToolRegistry.EMPTY);

    assertThatThrownBy(() -> Coordinator.start(wf, run(wf, RunMode.GREENFIELD), services))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("stage a gate build.gren: unknown gate 'build.gren'");
    assertThat(events.runIds()).isEmpty();
  }

  @Test
  void startRejectsMissingAgentsAndForeignRuns() {
    WorkflowDefinition wf =
        workflow("  - id: a\n    agent: A\n  - id: p\n    action: git.mergeToMain\n");
    Coordinator.Services noAgents =
        Coordinator.Services.of(events, artifacts, AgentRegistry.of(Map.of("A", producer("x"))));

    assertThatThrownBy(() -> Coordinator.start(wf, run(wf, RunMode.GREENFIELD), noAgents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no agent registered for: git.mergeToMain");

    Run other =
        new Run(
            "run-9",
            "another-workflow",
            RunMode.GREENFIELD,
            AutonomyLevel.L1_SUPERVISED,
            RunStatus.CREATED,
            wf.budgets());
    assertThatThrownBy(() -> Coordinator.start(wf, other, noAgents))
        .hasMessageContaining("is of workflow another-workflow");
    assertThat(events.runIds()).isEmpty(); // nothing was recorded
  }

  @Test
  void agentsSeeRunMetadataAndCanOnlyReadArtifacts() throws Exception {
    Map<String, Object> seen = new ConcurrentHashMap<>();
    start(
        workflow("  - id: a\n    agent: A\n  - id: b\n    agent: B\n    dependsOn: [a]\n"),
        RunMode.BROWNFIELD,
        Map.of(
            "A",
            producer("spec"),
            "B",
            ctx -> {
              seen.put("mode", ctx.run().mode());
              seen.put("attempt", ctx.attempt());
              seen.put("feedback", ctx.feedback());
              seen.put("spec", ctx.artifact("spec").isPresent());
              seen.put("missing", ctx.artifact("nothing").isPresent());
              seen.put("tool", ctx.tools().find(String.class).isPresent());
              return AgentResult.success("b");
            }));

    awaitStatus(RunStatus.COMPLETED);
    assertThat(seen)
        .containsEntry("mode", RunMode.BROWNFIELD)
        .containsEntry("attempt", 1)
        .containsEntry("feedback", List.of())
        .containsEntry("spec", true)
        .containsEntry("missing", false)
        .containsEntry("tool", false);
  }

  @Test
  void commandsAfterCloseAreRefused() {
    start(workflow("  - id: a\n    agent: A\n"), RunMode.GREENFIELD, Map.of("A", producer("x")));
    coordinator.close();

    assertThatThrownBy(() -> coordinator.submit(new Command.Resume(ALICE)))
        .isInstanceOf(IllegalStateException.class);
  }
}
