package com.acme.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.GateRef;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Retries with backoff, failure kinds, fallback, high-impact halts and loop-back. */
class RetryAndLoopTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final String FAST_RETRY =
      "    retry: { maxAttempts: 3, backoff: exponential, baseDelay: 10ms, maxDelay: 50ms,"
          + " jitter: false }\n";

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

  private void start(WorkflowDefinition wf, Map<String, Agent> agents, GateEvaluator gates) {
    coordinator =
        Coordinator.start(
            wf,
            Run.create("run-1", wf, RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED),
            new Coordinator.Services(
                events,
                artifacts,
                AgentRegistry.of(agents),
                gates,
                ToolRegistry.EMPTY,
                PolicyEngine.load(Path.of("..", "config"))));
  }

  private void start(WorkflowDefinition wf, Agent agent) {
    start(wf, Map.of("A", agent), GateEvaluator.PASS_ALL);
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

  // ---- retries --------------------------------------------------------------------------------

  @Test
  void aTransientFailureIsRetriedWithExponentialBackoff() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    start(
        workflow("  - id: a\n    agent: A\n" + FAST_RETRY),
        ctx -> {
          if (calls.incrementAndGet() < 3) {
            throw new TransientAgentException("429 Too Many Requests");
          }
          return ok("x");
        });

    awaitStatus(RunStatus.COMPLETED);
    assertThat(ofType(EventType.STAGE_RETRIED))
        .extracting(e -> e.payload().get("delay"), e -> e.payload().get("attempt"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("PT0.01S", 1L),
            org.assertj.core.groups.Tuple.tuple("PT0.02S", 2L));
    assertThat(ofType(EventType.STAGE_RETRIED).getFirst().payload())
        .containsEntry("kind", "TRANSIENT")
        .containsEntry("reason", "agent threw TransientAgentException: 429 Too Many Requests")
        .containsEntry("maxAttempts", 3L);
    assertThat(ofType(EventType.AGENT_INVOKED))
        .extracting(e -> e.payload().get("attempt"))
        .containsExactly(1L, 2L, 3L);
    assertThat(coordinator.state().stages().get("a").attempt()).isEqualTo(3);
  }

  @Test
  void aValidationFailureIsRetriedWithItsReasonAsFeedback() throws Exception {
    List<List<String>> feedback = new CopyOnWriteArrayList<>();
    start(
        workflow("  - id: a\n    agent: A\n" + FAST_RETRY),
        ctx -> {
          feedback.add(ctx.feedback());
          return ctx.attempt() == 1 ? AgentResult.failure("tasks have no ids") : ok("x");
        });

    awaitStatus(RunStatus.COMPLETED);
    assertThat(feedback).containsExactly(List.of(), List.of("tasks have no ids"));
    assertThat(ofType(EventType.STAGE_RETRIED).getFirst().payload())
        .containsEntry("kind", "VALIDATION");
  }

  @Test
  void aFailingExitGateCountsAsValidationAndIsRetried() throws Exception {
    AtomicInteger gateCalls = new AtomicInteger();
    start(
        workflow("  - id: a\n    agent: A\n    exitGates: [tests.green]\n" + FAST_RETRY),
        Map.of("A", ctx -> ok("x")),
        (gate, ctx) ->
            gateCalls.incrementAndGet() == 1
                ? GateResult.fail("2 tests failed")
                : GateResult.pass("all green"));

    awaitStatus(RunStatus.COMPLETED);
    assertThat(ofType(EventType.STAGE_RETRIED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry("reason", "exit gate tests.green failed: 2 tests failed"));
  }

  @Test
  void fatalFailuresAreNotRetried() throws Exception {
    start(
        workflow(
            "  - id: a\n    agent: A\n" + FAST_RETRY + "    fallback: { strategy: simpler }\n"),
        ctx -> {
          throw new IllegalStateException("no requirements_spec upstream");
        });

    awaitStatus(RunStatus.FAILED);
    assertThat(ofType(EventType.AGENT_INVOKED)).hasSize(1);
    assertThat(ofType(EventType.STAGE_RETRIED)).isEmpty();
    assertThat(ofType(EventType.FALLBACK_USED)).isEmpty();
    assertThat(ofType(EventType.STAGE_FAILED).getFirst().payload()).containsEntry("kind", "FATAL");
  }

  @Test
  void aPolicyDenyIsFatal() throws Exception {
    start(
        workflow("  - id: a\n    agent: A\n" + FAST_RETRY),
        ctx -> {
          ctx.authorize(new ProposedAction.Command("git push --force"));
          return ok("x");
        });

    awaitStatus(RunStatus.FAILED);
    assertThat(ofType(EventType.AGENT_INVOKED)).hasSize(1);
  }

  @Test
  void aTimeoutIsTransient() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    start(
        workflow("  - id: a\n    agent: A\n    timeout: 100ms\n" + FAST_RETRY),
        ctx -> {
          if (calls.incrementAndGet() == 1) {
            new java.util.concurrent.CountDownLatch(1).await(); // hangs until cancelled
          }
          return ok("x");
        });

    awaitStatus(RunStatus.COMPLETED);
    assertThat(ofType(EventType.STAGE_RETRIED).getFirst().payload())
        .containsEntry("kind", "TRANSIENT")
        .containsEntry("reason", "timed out after PT0.1S");
  }

  // ---- fallback -------------------------------------------------------------------------------

  @Test
  void afterRetriesTheFallbackStrategyGetsOneTry() throws Exception {
    List<String> strategies = new CopyOnWriteArrayList<>();
    start(
        workflow(
            "  - id: impl\n    agent: A\n"
                + FAST_RETRY.replace("maxAttempts: 3", "maxAttempts: 2")
                + "    fallback: { strategy: minimal-patch }\n"),
        ctx -> {
          strategies.add(ctx.fallbackStrategy().orElse("-"));
          return ctx.fallbackStrategy().isPresent() ? ok("patch") : AgentResult.failure("too big");
        });

    awaitStatus(RunStatus.COMPLETED);
    assertThat(strategies).containsExactly("-", "-", "minimal-patch");
    assertThat(ofType(EventType.FALLBACK_USED))
        .singleElement()
        .satisfies(e ->
            assertThat(e.payload())
                .containsEntry("strategy", "minimal-patch")
                .containsEntry("reason", "too big"));
    assertThat(ofType(EventType.AGENT_INVOKED).getLast().payload())
        .containsEntry("fallback", "minimal-patch")
        .containsEntry("attempt", 3L);
  }

  @Test
  void aFailedFallbackFailsTheStage() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    start(
        workflow("  - id: impl\n    agent: A\n    fallback: { strategy: minimal-patch }\n"),
        ctx -> {
          calls.incrementAndGet();
          return AgentResult.failure("still too big");
        });

    awaitStatus(RunStatus.FAILED);
    assertThat(calls).hasValue(2); // one attempt (no retry configured) + the fallback
    assertThat(ofType(EventType.FALLBACK_USED)).hasSize(1);
    assertThat(ofType(EventType.STAGE_FAILED).getFirst().payload())
        .containsEntry("reason", "still too big");
  }

  // ---- high impact ----------------------------------------------------------------------------

  @Test
  void aHighImpactFailureHaltsTheRunForAHuman() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    start(
        workflow("  - id: risky\n    agent: A\n    highImpact: true\n"),
        ctx ->
            calls.incrementAndGet() == 1
                ? AgentResult.failure("merge conflict", FailureKind.FATAL)
                : ok("x"));

    RunState halted = awaitStatus(RunStatus.HALTED);
    assertThat(halted.statuses()).containsEntry("risky", StageStatus.HALTED);
    assertThat(ofType(EventType.STAGE_FAILED)).isEmpty();
    assertThat(ofType(EventType.RUN_HALTED).getFirst().payload())
        .containsEntry("reason", "high-impact stage risky failed: merge conflict")
        .containsEntry("stageId", "risky");

    coordinator.submit(new Command.Resume(Actor.human("alice")));
    awaitStatus(RunStatus.COMPLETED);
  }

  // ---- loop-back ------------------------------------------------------------------------------

  private static final String LOOP =
      """
        - id: impl
          agent: Impl
        - id: val
          agent: Val
          dependsOn: [impl]
          exitGates: [tests.green]
          onFailure: { loopTo: impl, maxLoops: 2, feedback: validation_report }
        - id: docs
          agent: Impl
          dependsOn: [val]
      """;

  /** Passes once the report says green. */
  private static GateResult greenGate(GateRef gate, GateContext ctx) {
    return ctx.artifact("validation_report").orElseThrow().contentJson().contains("\"green\":true")
        ? GateResult.pass("green")
        : GateResult.fail("1 test failed");
  }

  @Test
  void aValidationFailureLoopsBackToImplementationWithTheReport() throws Exception {
    List<List<String>> implFeedback = new CopyOnWriteArrayList<>();
    AtomicInteger validations = new AtomicInteger();
    Agent impl =
        ctx -> {
          if (ctx.stageKey().equals("impl")) {
            implFeedback.add(ctx.feedback());
          }
          return ok(ctx.stageKey().equals("impl") ? "patch" : "docs");
        };
    Agent val =
        ctx ->
            AgentResult.success(
                "validated",
                ArtifactOutput.of(
                    "validation_report",
                    "{\"green\":" + (validations.incrementAndGet() > 1) + "}",
                    "test"));
    start(workflow(LOOP), Map.of("Impl", impl, "Val", val), RetryAndLoopTest::greenGate);

    RunState end = awaitStatus(RunStatus.COMPLETED);
    assertThat(implFeedback)
        .containsExactly(
            List.of(),
            List.of(
                "val failed: exit gate tests.green failed: 1 test failed"
                    + " (see run-1:val:validation_report:v1)"));
    assertThat(validations).hasValue(2);
    assertThat(end.stages().get("impl").loopCount()).isEqualTo(1);
    assertThat(end.stages().get("val").loopCount()).isEqualTo(1);
    assertThat(artifacts.versions("run-1", "impl", "patch")).hasSize(2);
    assertThat(ofType(EventType.STAGE_INVALIDATED))
        .extracting(e -> e.stageId().orElseThrow())
        .containsExactly("val", "impl");
    assertThat(ofType(EventType.STAGE_INVALIDATED).getFirst().payload())
        .containsEntry("loopTo", "impl")
        .containsEntry("loop", 1L);
  }

  @Test
  void whenTheLoopsAreUsedUpTheStageFails() throws Exception {
    AtomicInteger implRuns = new AtomicInteger();
    Agent impl =
        ctx -> {
          implRuns.incrementAndGet();
          return ok("patch");
        };
    Agent val =
        ctx ->
            AgentResult.success(
                "validated", ArtifactOutput.of("validation_report", "{\"green\":false}", "test"));
    start(workflow(LOOP), Map.of("Impl", impl, "Val", val), RetryAndLoopTest::greenGate);

    RunState end = awaitStatus(RunStatus.FAILED);
    assertThat(implRuns).hasValue(3); // the first run + 2 loops
    assertThat(end.statuses()).containsEntry("val", StageStatus.FAILED);
    assertThat(end.statuses()).doesNotContainKey("docs");
    assertThat(ofType(EventType.STAGE_FAILED).getFirst().payload())
        .containsEntry("reason", "exit gate tests.green failed: 1 test failed")
        .containsEntry("kind", "VALIDATION");
  }

  @Test
  void aTransientValidationFailureIsRetriedNotLoopedBack() throws Exception {
    AtomicInteger implRuns = new AtomicInteger();
    AtomicInteger valRuns = new AtomicInteger();
    Agent impl =
        ctx -> {
          implRuns.incrementAndGet();
          return ok(ctx.stageKey().equals("impl") ? "patch" : "docs");
        };
    Agent val =
        ctx -> {
          if (valRuns.incrementAndGet() == 1) {
            throw new TransientAgentException("maven repo unreachable");
          }
          return AgentResult.success(
              "validated", ArtifactOutput.of("validation_report", "{\"green\":true}", "test"));
        };
    start(
        workflow(
            LOOP.replace(
                "    onFailure:",
                "    retry: { maxAttempts: 2, baseDelay: 10ms }\n    onFailure:")),
        Map.of("Impl", impl, "Val", val),
        RetryAndLoopTest::greenGate);

    awaitStatus(RunStatus.COMPLETED);
    assertThat(implRuns).hasValue(2); // impl + docs, no loop
    assertThat(valRuns).hasValue(2);
    assertThat(ofType(EventType.STAGE_INVALIDATED)).isEmpty();
  }
}
