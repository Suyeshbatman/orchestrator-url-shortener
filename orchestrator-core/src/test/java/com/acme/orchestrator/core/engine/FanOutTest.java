package com.acme.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.checkpoint.InMemoryCheckpointManager;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.model.ArtifactTypes;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A stage marked {@code expand: tasks} fans out into one sub-stage per code task. */
class FanOutTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final String SRC = "shortener-service/src/main/java/";

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

  /** decompose → impl (fanned out) → after; validate loops back to impl once if asked. */
  private static WorkflowDefinition workflow(String extra) {
    return new WorkflowLoader()
        .parse(
            """
            workflow: test
            budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
            stages:
              - id: decompose
                agent: D
              - id: impl
                agent: I
                dependsOn: [decompose]
                expand: tasks
                checkpoint: git
                retry: { maxAttempts: 3, backoff: fixed, baseDelay: 10ms, jitter: false }
              - id: after
                agent: Z
                dependsOn: [impl]
            """
                + extra,
            "test.yml");
  }

  private static final String THREE_TASKS =
      """
      {"tasks":[
        {"id":"T1","title":"model","type":"CODE","dependsOn":[],"estimatedFiles":["%sA.java"]},
        {"id":"T2","title":"api","type":"CODE","dependsOn":[],"estimatedFiles":["%sB.java"]},
        {"id":"T3","title":"tests","type":"TEST","dependsOn":["T1"],"estimatedFiles":["T.java"]},
        {"id":"T4","title":"more model","type":"CONFIG","dependsOn":[],"estimatedFiles":["%sA.java"]}
      ]}
      """
          .formatted(SRC, SRC, SRC);

  private static Agent decompose(String taskGraph) {
    return ctx ->
        AgentResult.success(
            "decomposed", ArtifactOutput.of(ArtifactTypes.TASK_GRAPH, taskGraph, "test"));
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

  private RunState awaitStatus(RunStatus status) throws Exception {
    return coordinator.await(s -> s.run().status() == status, WAIT);
  }

  private List<Event> ofType(EventType type) {
    return events.events("run-1").stream().filter(e -> e.type() == type).toList();
  }

  private List<String> keysOf(EventType type) {
    return ofType(type).stream().map(e -> e.stageId().orElseThrow()).toList();
  }

  private static AgentResult ok() {
    return AgentResult.success("ok");
  }

  /** Writes {@code path} through the policy engine, then into the workspace. */
  private void write(StageContext ctx, String path, String content) {
    if (!ctx.authorize(new ProposedAction.FileWrite(path, content)).denied()) {
      workspace.write(path, content);
    }
  }

  // ---- planning -------------------------------------------------------------------------------

  @Test
  void onlyCodeTasksAreExpandedAndTasksSharingAFileAreChained() {
    var graph =
        artifacts.save(
            new com.acme.orchestrator.core.artifact.ArtifactDraft(
                "run-1",
                "decompose",
                ArtifactTypes.TASK_GRAPH,
                THREE_TASKS,
                com.acme.orchestrator.core.model.ProducedBy.deterministic("test"),
                List.of(),
                "test"));

    TaskExpansion x = TaskExpansion.plan("impl", graph);

    assertThat(x.keys()).containsExactly("impl.T1", "impl.T2", "impl.T4"); // the TEST task isn't
    assertThat(x.task("T4").orElseThrow().dependsOn()).containsExactly("T1"); // both write A.java
    assertThat(x.task("T2").orElseThrow().dependsOn()).isEmpty();
    assertThat(x.taskGraphId()).isEqualTo(graph.id());
  }

  @Test
  void chainingKeepsExistingOrderAndAddsNoRedundantEdges() {
    List<TaskExpansion.Task> chained =
        TaskExpansion.chainOverlapping(
            List.of(
                new TaskExpansion.Task("T1", "a", List.of(), List.of("x")),
                new TaskExpansion.Task("T2", "b", List.of("T1"), List.of("x")),
                new TaskExpansion.Task("T3", "c", List.of("T2"), List.of("x", "y")),
                new TaskExpansion.Task("T4", "d", List.of(), List.of("y"))));

    assertThat(chained.get(1).dependsOn()).containsExactly("T1"); // already there
    assertThat(chained.get(2).dependsOn()).containsExactly("T2"); // T1 reached through T2
    assertThat(chained.get(3).dependsOn()).containsExactly("T3"); // shares y
  }

  @Test
  void keysNameTheStageAndTheTask() {
    assertThat(TaskExpansion.key("implementation", "T3")).isEqualTo("implementation.T3");
    assertThat(TaskExpansion.stageIdOf("implementation.T3")).isEqualTo("implementation");
    assertThat(TaskExpansion.stageIdOf("design")).isEqualTo("design");
    assertThat(TaskExpansion.taskIdOf("implementation.T3")).contains("T3");
    assertThat(TaskExpansion.taskIdOf("design")).isEmpty();
  }

  // ---- running --------------------------------------------------------------------------------

  @Test
  void disjointTasksRunInParallelOverlappingOnesInOrderThenTheJoin() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    List<String> seen = new CopyOnWriteArrayList<>();
    List<String> order = new CopyOnWriteArrayList<>();
    start(
        workflow(""),
        Map.of(
            "D",
            decompose(THREE_TASKS),
            "I",
            ctx -> {
              String task = ctx.task().orElseThrow().id();
              seen.add(ctx.stageKey() + " " + ctx.task().orElseThrow().files());
              if (!task.equals("T4")) {
                bothStarted.countDown(); // T1 and T2 both reach here before either finishes
                if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("T1 and T2 did not run in parallel");
                }
              }
              order.add(task);
              return ok();
            },
            "Z",
            ctx -> {
              order.add("after");
              return ok();
            }));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    Event expanded = ofType(EventType.STAGE_EXPANDED).getFirst();
    assertThat(expanded.stageId()).contains("impl");
    assertThat(TaskExpansion.fromEvent(expanded).keys())
        .containsExactly("impl.T1", "impl.T2", "impl.T4");
    assertThat(seen).contains("impl.T2 [" + SRC + "B.java]");
    assertThat(order.indexOf("T4")).isGreaterThan(order.indexOf("T1"));
    assertThat(order.getLast()).isEqualTo("after");
    assertThat(end.stages())
        .containsKeys("impl.T1", "impl.T2", "impl.T4")
        .doesNotContainKey("impl");
    assertThat(end.stages().get("impl.T4").status()).isEqualTo(StageStatus.SUCCEEDED);
    // each task keeps its own checkpoint, taken as it became ready
    assertThat(keysOf(EventType.CHECKPOINT_CREATED))
        .containsExactlyInAnyOrder("impl.T1", "impl.T2", "impl.T4");
  }

  @Test
  void withoutCodeTasksTheStageRunsOnceUnexpanded() throws Exception {
    List<String> keys = new CopyOnWriteArrayList<>();
    start(
        workflow(""),
        Map.of(
            "D",
            decompose("{\"tasks\":[{\"id\":\"T1\",\"type\":\"DOC\",\"estimatedFiles\":[]}]}"),
            "I",
            ctx -> {
              keys.add(ctx.stageKey() + ctx.task().map(t -> " " + t.id()).orElse(""));
              return ok();
            },
            "Z",
            ctx -> ok()));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    assertThat(keys).containsExactly("impl");
    assertThat(end.stages().get("impl").status()).isEqualTo(StageStatus.SUCCEEDED);
    assertThat(ofType(EventType.STAGE_EXPANDED).getFirst().payload().get("tasks"))
        .asList()
        .isEmpty();
  }

  @Test
  void aTaskWritingAFileASiblingIsWritingIsRetriedAndOnlyItsOwnFilesAreRestored() throws Exception {
    String twoTasks =
        """
        {"tasks":[
          {"id":"T1","type":"CODE","estimatedFiles":["%sA.java"]},
          {"id":"T2","type":"CODE","estimatedFiles":["%sB.java"]}
        ]}
        """
            .formatted(SRC, SRC);
    CountDownLatch t1Wrote = new CountDownLatch(1);
    CountDownLatch t2Done = new CountDownLatch(1);
    AtomicInteger t2Attempts = new AtomicInteger();
    start(
        workflow(""),
        Map.of(
            "D",
            decompose(twoTasks),
            "I",
            ctx -> {
              if (ctx.task().orElseThrow().id().equals("T1")) {
                write(ctx, SRC + "A.java", "a");
                write(ctx, SRC + "Shared.java", "from T1"); // not in its estimate
                t1Wrote.countDown();
                t2Done.await(5, TimeUnit.SECONDS); // still running while T2 tries
                return ok();
              }
              t1Wrote.await(5, TimeUnit.SECONDS);
              if (t2Attempts.incrementAndGet() == 1) {
                try {
                  write(ctx, SRC + "B.java", "b");
                  write(ctx, SRC + "Shared.java", "from T2"); // conflicts: T1 holds it
                } finally {
                  t2Done.countDown();
                }
              } else {
                write(ctx, SRC + "B.java", "b2");
              }
              return ok();
            },
            "Z",
            ctx -> ok()));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    assertThat(end.stages().get("impl.T2").attempt()).isEqualTo(2);
    assertThat(ofType(EventType.STAGE_RETRIED).getFirst().payload())
        .containsEntry("kind", "VALIDATION")
        .hasEntrySatisfying(
            "reason",
            r -> assertThat((String) r).contains("write conflict: " + SRC + "Shared.java"));
    // T2's failed attempt was undone file by file: T1's work stayed
    assertThat(workspace.files())
        .containsEntry(SRC + "A.java", "a")
        .containsEntry(SRC + "Shared.java", "from T1")
        .containsEntry(SRC + "B.java", "b2");
  }

  @Test
  void aLoopBackToAFannedOutStageReRunsEveryTask() throws Exception {
    String twoTasks =
        """
        {"tasks":[
          {"id":"T1","type":"CODE","estimatedFiles":["a"]},
          {"id":"T2","type":"CODE","estimatedFiles":["b"]}
        ]}
        """;
    AtomicInteger validations = new AtomicInteger();
    List<String> feedback = new CopyOnWriteArrayList<>();
    start(
        workflow(
            """
              - id: validate
                agent: V
                dependsOn: [after]
                retry: { maxAttempts: 1 }
                onFailure: { loopTo: impl, maxLoops: 1 }
            """),
        Map.of(
            "D",
            decompose(twoTasks),
            "I",
            ctx -> {
              feedback.addAll(ctx.feedback());
              return ok();
            },
            "Z",
            ctx -> ok(),
            "V",
            ctx -> {
              if (validations.incrementAndGet() == 1) {
                throw new AgentValidationException("tests failed");
              }
              return ok();
            }));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    assertThat(end.stages().get("impl.T1").loopCount()).isEqualTo(1);
    assertThat(end.stages().get("impl.T2").loopCount()).isEqualTo(1);
    assertThat(keysOf(EventType.AGENT_INVOKED).stream().filter(k -> k.startsWith("impl.")))
        .containsExactlyInAnyOrder("impl.T1", "impl.T2", "impl.T1", "impl.T2");
    assertThat(feedback).hasSize(2).allMatch(f -> f.startsWith("validate failed: "));
  }

  @Test
  void aRestartedCoordinatorRebuildsTheFanOutFromTheLog() throws Exception {
    String twoTasks =
        """
        {"tasks":[
          {"id":"T1","type":"CODE","estimatedFiles":["a"]},
          {"id":"T2","type":"CODE","dependsOn":["T1"],"estimatedFiles":["b"]}
        ]}
        """;
    CountDownLatch t2Started = new CountDownLatch(1);
    WorkflowDefinition wf = workflow("");
    start(
        wf,
        Map.of(
            "D",
            decompose(twoTasks),
            "I",
            ctx -> {
              if (ctx.task().orElseThrow().id().equals("T2")) {
                t2Started.countDown();
                new CountDownLatch(1).await(); // until the "crash"
              }
              return ok();
            },
            "Z",
            ctx -> ok()));
    assertThat(t2Started.await(5, TimeUnit.SECONDS)).isTrue();
    coordinator.close(); // the process dies while T2 runs

    List<String> rerun = new CopyOnWriteArrayList<>();
    start(
        wf,
        Map.of(
            "D",
            decompose(twoTasks),
            "I",
            ctx -> {
              rerun.add(ctx.stageKey() + " " + ctx.task().orElseThrow().id());
              return ok();
            },
            "Z",
            ctx -> ok()));

    RunState end = awaitStatus(RunStatus.COMPLETED);
    assertThat(rerun).containsExactly("impl.T2 T2"); // T1 had succeeded; not expanded twice
    assertThat(ofType(EventType.STAGE_EXPANDED)).hasSize(1);
    assertThat(end.stages().get("impl.T2").attempt()).isEqualTo(2);
  }

  @Test
  void aChangedTaskGraphReExpandsTheStage() throws Exception {
    AtomicInteger decompositions = new AtomicInteger();
    List<String> implemented = new CopyOnWriteArrayList<>();
    start(
        workflow(
            """
              - id: validate
                agent: V
                dependsOn: [after]
                retry: { maxAttempts: 1 }
                onFailure: { loopTo: decompose, maxLoops: 1 }
            """),
        Map.of(
            "D",
            ctx ->
                decompose(
                        decompositions.incrementAndGet() == 1
                            ? "{\"tasks\":[{\"id\":\"T1\",\"type\":\"CODE\"}]}"
                            : "{\"tasks\":[{\"id\":\"T1\",\"type\":\"CODE\"},"
                                + "{\"id\":\"T2\",\"type\":\"CODE\"}]}")
                    .execute(ctx),
            "I",
            ctx -> {
              implemented.add(ctx.stageKey());
              return ok();
            },
            "Z",
            ctx -> ok(),
            "V",
            ctx -> {
              if (implemented.size() == 1) {
                throw new AgentValidationException("a task is missing");
              }
              return ok();
            }));

    RunState end = awaitStatus(RunStatus.COMPLETED);

    assertThat(ofType(EventType.STAGE_EXPANDED)).hasSize(2);
    assertThat(implemented).containsExactly("impl.T1", "impl.T1", "impl.T2");
    assertThat(end.stages().get("impl.T2").status()).isEqualTo(StageStatus.SUCCEEDED);
  }

  // ---- write claims ---------------------------------------------------------------------------

  @Test
  void writeClaimsRefuseOnlyLiveOrNewerWritesOfAnotherTask() {
    WriteClaims claims = new WriteClaims();
    claims.begin("s.T1");
    claims.begin("s.T2");
    claims.claim("s.T1", "x");
    claims.claim("s.T1", "x"); // its own file again
    assertThatThrownBy(() -> claims.claim("s.T2", "x"))
        .isInstanceOf(AgentValidationException.class)
        .hasMessageContaining("x was also written by s.T1");

    claims.settle("s.T1");
    assertThatThrownBy(() -> claims.claim("s.T2", "x")) // written after T2 started
        .isInstanceOf(AgentValidationException.class);

    claims.begin("s.T2"); // a retry starts from T1's file
    claims.claim("s.T2", "x");

    claims.release("s.T2");
    claims.begin("s.T3");
    claims.claim("s.T3", "x");
  }
}
