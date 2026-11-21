package com.acme.orchestrator.core.engine;

import static com.acme.orchestrator.core.engine.Command.ApprovalDecision.APPROVER;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.checkpoint.InMemoryCheckpointManager;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.MutableClock;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Safe stop, kill, checkpoints and rollback, budgets, the denial limit and crash recovery. */
class SafeStopAndRecoveryTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Actor ALICE = Actor.human("alice");
  private static final PolicyEngine POLICIES = PolicyEngine.load(Path.of("..", "config"));
  private static final String FILE = "shortener-service/src/main/java/com/acme/shortener/Qr.java";
  private static final String BUDGETS = "{ maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 10000 }";
  private static final String FAST_RETRY =
      "    retry: { maxAttempts: 2, baseDelay: 10ms, maxDelay: 10ms, jitter: false }\n";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
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

  private static WorkflowDefinition workflow(String budgets, String stagesYaml) {
    return new WorkflowLoader()
        .parse("workflow: test\nbudgets: " + budgets + "\nstages:\n" + stagesYaml, "test.yml");
  }

  private static WorkflowDefinition workflow(String stagesYaml) {
    return workflow(BUDGETS, stagesYaml);
  }

  private Coordinator start(WorkflowDefinition wf, Map<String, Agent> agents, Duration grace) {
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
                    POLICIES)
                .withCheckpoints(workspace)
                .withSafeStopGrace(grace));
    return coordinator;
  }

  private RunState awaitStatus(RunStatus status) throws Exception {
    return coordinator.await(s -> s.run().status() == status, WAIT);
  }

  private List<Event> ofType(EventType type) {
    return events.events("run-1").stream().filter(e -> e.type() == type).toList();
  }

  private static AgentResult ok(String type) {
    return AgentResult.success("ok", ArtifactOutput.of(type, "{}", "test"));
  }

  // ---- safe stop ------------------------------------------------------------------------------

  @Test
  void aHaltLetsRunningAttemptsFinishThenCheckpointsAndHalts() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Agent slow =
        ctx -> {
          started.countDown();
          release.await();
          return ok("a");
        };
    start(
        workflow("  - id: a\n    agent: Slow\n  - id: b\n    agent: Fast\n    dependsOn: [a]\n"),
        Map.of("Slow", slow, "Fast", ctx -> ok("b")),
        Duration.ofSeconds(10));
    started.await(5, TimeUnit.SECONDS);

    coordinator.submit(new Command.Halt("end of day", ALICE));
    coordinator.await(s -> !ofType(EventType.SAFE_STOP_REQUESTED).isEmpty(), WAIT);
    assertThat(coordinator.state().run().status()).isEqualTo(RunStatus.RUNNING);
    assertThat(ofType(EventType.SAFE_STOP_REQUESTED).getFirst().payload())
        .containsEntry("running", List.of("a"))
        .containsEntry("grace", "PT10S");

    release.countDown(); // a finishes within the grace period
    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.SUCCEEDED).doesNotContainKey("b");
    assertThat(ofType(EventType.CHECKPOINT_CREATED).getLast().payload())
        .containsEntry("reason", "safe stop");
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("reason", "end of day")
        .containsEntry("checkpointId", "cp-1")
        .doesNotContainKey("cancelled");
    assertThat(ofType(EventType.RUN_HALTED).getFirst().actor()).isEqualTo(ALICE);

    coordinator.submit(new Command.Resume(ALICE));
    awaitStatus(RunStatus.COMPLETED);
  }

  @Test
  void afterTheGracePeriodRunningAttemptsAreCancelled() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch started = new CountDownLatch(1);
    Agent stuck =
        ctx -> {
          if (calls.incrementAndGet() == 1) {
            started.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException e) {
              interrupted.set(true);
              throw e;
            }
          }
          return ok("a");
        };
    start(workflow("  - id: a\n    agent: A\n"), Map.of("A", stuck), Duration.ofMillis(100));
    started.await(5, TimeUnit.SECONDS);

    coordinator.submit(new Command.Halt("deploy freeze", ALICE));
    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.HALTED);
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("cancelled", List.of("a"));
    coordinator.await(s -> interrupted.get(), WAIT);

    coordinator.submit(new Command.Resume(ALICE));
    awaitStatus(RunStatus.COMPLETED);
    assertThat(calls).hasValue(2);
  }

  @Test
  void repeatedPolicyDenialsSafeStopTheRun() throws Exception {
    Agent reckless =
        ctx -> {
          for (String path :
              List.of(".github/workflows/ci.yml", "config/policies.yml", "pom.xml")) {
            ctx.authorize(new ProposedAction.FileWrite(path, "x"));
          }
          return ok("patch");
        };
    start(workflow("  - id: impl\n    agent: A\n"), Map.of("A", reckless), Duration.ofSeconds(10));

    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("impl", StageStatus.HALTED);
    assertThat(ofType(EventType.STAGE_FAILED)).isEmpty();
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("reason", "3 policy denials since the last resume");
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload().get("notes").toString())
        .contains("impl: policy denied: file_write .github/workflows/ci.yml");
  }

  // ---- checkpoints and rollback ---------------------------------------------------------------

  @Test
  void aFailedAttemptThatWroteFilesIsRolledBackBeforeTheRetry() throws Exception {
    AtomicBoolean secondSawCleanWorkspace = new AtomicBoolean();
    Agent impl =
        ctx -> {
          if (ctx.attempt() == 2) {
            secondSawCleanWorkspace.set(workspace.read(FILE).isEmpty());
          }
          ctx.authorize(new ProposedAction.FileWrite(FILE, "attempt " + ctx.attempt()));
          workspace.write(FILE, "attempt " + ctx.attempt());
          return ctx.attempt() == 1 ? AgentResult.failure("does not compile") : ok("patch");
        };
    start(
        workflow("  - id: impl\n    agent: A\n    checkpoint: git\n" + FAST_RETRY),
        Map.of("A", impl),
        Duration.ofSeconds(10));

    awaitStatus(RunStatus.COMPLETED);
    assertThat(ofType(EventType.CHECKPOINT_CREATED).getFirst().payload())
        .containsEntry("checkpointId", "cp-1")
        .containsEntry("before", "impl");
    assertThat(ofType(EventType.STAGE_RETRIED).getFirst().payload())
        .containsEntry("restoredCheckpoint", "cp-1");
    assertThat(secondSawCleanWorkspace).isTrue();
    assertThat(workspace.read(FILE)).contains("attempt 2");
  }

  @Test
  void aFailedAttemptThatWroteNothingRestoresNothing() throws Exception {
    start(
        workflow("  - id: impl\n    agent: A\n    checkpoint: git\n"),
        Map.of("A", ctx -> AgentResult.failure("no idea")),
        Duration.ofSeconds(10));

    awaitStatus(RunStatus.FAILED);
    assertThat(workspace.restored()).isEmpty();
    assertThat(ofType(EventType.STAGE_FAILED).getFirst().payload())
        .doesNotContainKey("restoredCheckpoint");
  }

  @Test
  void rollbackRestoresTheCheckpointAndReRunsTheStageAndItsDownstream() throws Exception {
    AtomicInteger implRuns = new AtomicInteger();
    Agent impl =
        ctx -> {
          int run = implRuns.incrementAndGet();
          ctx.authorize(new ProposedAction.FileWrite(FILE, "v" + run));
          workspace.write(FILE, "v" + run);
          return ok("patch");
        };
    start(
        workflow(
            """
              - id: design
                agent: Other
              - id: impl
                agent: Impl
                dependsOn: [design]
                checkpoint: git
              - id: docs
                agent: Other
                dependsOn: [impl]
                approval: { required: true, reason: "docs review" }
            """),
        Map.of("Impl", impl, "Other", ctx -> ok(ctx.stageKey())),
        Duration.ofSeconds(10));
    awaitStatus(RunStatus.AWAITING_HUMAN);
    assertThat(workspace.read(FILE)).contains("v1");

    coordinator.submit(new Command.Rollback("design", "no checkpoint there", ALICE)); // ignored
    coordinator.submit(new Command.Rollback("impl", "wrong approach", ALICE));
    coordinator.await(
        s -> s.stages().get("docs").status() == StageStatus.WAITING_APPROVAL && implRuns.get() == 2,
        WAIT);

    assertThat(ofType(EventType.ROLLED_BACK))
        .extracting(e -> e.stageId().orElseThrow())
        .containsExactly("impl", "docs");
    assertThat(ofType(EventType.ROLLED_BACK).getFirst().payload())
        .containsEntry("checkpointId", "cp-1")
        .containsEntry("reason", "wrong approach")
        .containsEntry("rolledBackTo", "impl");
    assertThat(workspace.restored()).hasSize(1);
    assertThat(workspace.read(FILE)).contains("v2");

    coordinator.submit(
        new Command.ApprovalDecision("docs", ALICE, APPROVER, Decision.APPROVE, "ok"));
    RunState end = awaitStatus(RunStatus.COMPLETED);
    assertThat(end.stages().get("design").attempt()).isEqualTo(1); // upstream untouched
  }

  // ---- budgets --------------------------------------------------------------------------------

  @Test
  void runningOutOfLlmCallsSafeStopsInsteadOfFailing() throws Exception {
    Agent chatty =
        ctx -> {
          for (int i = 0; i < 3; i++) {
            ctx.beforeLlmCall();
            ctx.recordLlmCall(100, 50);
          }
          return ok("spec");
        };
    start(
        workflow(
            "{ maxWallClock: 10m, maxLlmCalls: 2, maxTokens: 10000 }", "  - id: a\n    agent: A\n"),
        Map.of("A", chatty),
        Duration.ofSeconds(10));

    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.HALTED);
    assertThat(ofType(EventType.STAGE_FAILED)).isEmpty();
    assertThat(ofType(EventType.USAGE_RECORDED).getFirst().payload())
        .containsEntry("llmCalls", 2L)
        .containsEntry("tokens", 300L);
    assertThat(ofType(EventType.BUDGET_EXCEEDED).getFirst().payload().get("reason").toString())
        .contains("LLM call budget used up (2 of 2)");
  }

  @Test
  void exceedingTheTokenBudgetSafeStopsTheRun() throws Exception {
    Agent greedy =
        ctx -> {
          ctx.beforeLlmCall();
          ctx.recordLlmCall(900, 600);
          return ok("spec");
        };
    start(
        workflow(
            "{ maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }",
            "  - id: a\n    agent: A\n  - id: b\n    agent: A\n    dependsOn: [a]\n"),
        Map.of("A", greedy),
        Duration.ofSeconds(10));

    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("a", StageStatus.SUCCEEDED).doesNotContainKey("b");
    assertThat(ofType(EventType.BUDGET_EXCEEDED).getFirst().payload())
        .containsEntry("budget", "tokens")
        .containsEntry("limit", "1000")
        .containsEntry("used", "1500");
  }

  @Test
  void theWallClockBudgetIsMeasuredWithTheLogsClock() throws Exception {
    start(
        workflow("  - id: a\n    agent: A\n    approval: { required: true, reason: r }\n"),
        Map.of("A", ctx -> ok("x")),
        Duration.ofSeconds(10));
    awaitStatus(RunStatus.AWAITING_HUMAN);

    clock.advance(Duration.ofMinutes(11));
    coordinator.submit(new Command.Wake());

    awaitStatus(RunStatus.HALTED);
    assertThat(ofType(EventType.BUDGET_EXCEEDED).getFirst().payload())
        .containsEntry("budget", "wallClock")
        .containsEntry("limit", "PT10M")
        .containsEntry("used", "PT11M");
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("reason", "wallClock budget exceeded: used PT11M of PT10M");
  }

  // ---- crash recovery -------------------------------------------------------------------------

  @Test
  void aRestartedCoordinatorRetriesTheAttemptThatWasRunning() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    WorkflowDefinition wf = workflow("  - id: a\n    agent: A\n" + FAST_RETRY);
    start(
        wf,
        Map.of(
            "A",
            ctx -> {
              started.countDown();
              new CountDownLatch(1).await(); // until the "crash"
              return ok("x");
            }),
        Duration.ofSeconds(10));
    started.await(5, TimeUnit.SECONDS);
    coordinator.close(); // the process dies mid-attempt

    clock.advance(Duration.ofSeconds(30));
    start(wf, Map.of("A", ctx -> ok("x")), Duration.ofSeconds(10));

    RunState end = awaitStatus(RunStatus.COMPLETED);
    assertThat(end.stages().get("a").attempt()).isEqualTo(2);
    Event retried = ofType(EventType.STAGE_RETRIED).getFirst();
    assertThat(retried.payload())
        .containsEntry(
            "reason", "interrupted: the coordinator stopped while this attempt was running")
        .containsEntry("kind", "TRANSIENT");
    // the log carries what MTTR needs: when the failure was detected and when it recovered
    Event recovered = ofType(EventType.STAGE_SUCCEEDED).getFirst();
    assertThat(
            Duration.between(
                ofType(EventType.STAGE_STARTED).getFirst().timestamp(), retried.timestamp()))
        .isEqualTo(Duration.ofSeconds(30));
    assertThat(recovered.timestamp()).isAfterOrEqualTo(retried.timestamp());
  }

  @Test
  void aSafeStopInterruptedByACrashFinishesOnRestart() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    WorkflowDefinition wf = workflow("  - id: a\n    agent: A\n");
    start(
        wf,
        Map.of(
            "A",
            ctx -> {
              started.countDown();
              new CountDownLatch(1).await();
              return ok("x");
            }),
        Duration.ofSeconds(30));
    started.await(5, TimeUnit.SECONDS);
    coordinator.submit(new Command.Halt("maintenance", ALICE));
    coordinator.await(s -> !ofType(EventType.SAFE_STOP_REQUESTED).isEmpty(), WAIT);
    coordinator.close(); // dies during the grace period

    start(wf, Map.of("A", ctx -> ok("x")), Duration.ofSeconds(30));

    awaitStatus(RunStatus.HALTED);
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("reason", "maintenance");
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload().get("notes").toString())
        .contains("interrupted");
    assertThat(ofType(EventType.AGENT_INVOKED)).hasSize(1); // nothing new was started
  }
}
