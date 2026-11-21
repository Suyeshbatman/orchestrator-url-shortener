package com.acme.orchestrator.core.engine;

import static com.acme.orchestrator.core.event.EventType.AGENT_INVOKED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_GRANTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REJECTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REQUESTED;
import static com.acme.orchestrator.core.event.EventType.ARTIFACT_PRODUCED;
import static com.acme.orchestrator.core.event.EventType.GATE_EVALUATED;
import static com.acme.orchestrator.core.event.EventType.REPLAN_TRIGGERED;
import static com.acme.orchestrator.core.event.EventType.RUN_COMPLETED;
import static com.acme.orchestrator.core.event.EventType.RUN_CREATED;
import static com.acme.orchestrator.core.event.EventType.RUN_FAILED;
import static com.acme.orchestrator.core.event.EventType.RUN_HALTED;
import static com.acme.orchestrator.core.event.EventType.RUN_RESUMED;
import static com.acme.orchestrator.core.event.EventType.STAGE_FAILED;
import static com.acme.orchestrator.core.event.EventType.STAGE_READY;
import static com.acme.orchestrator.core.event.EventType.STAGE_SKIPPED;
import static com.acme.orchestrator.core.event.EventType.STAGE_STARTED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SUCCEEDED;

import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.dag.WorkflowGraph;
import com.acme.orchestrator.core.engine.Command.AmendRequirement;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision;
import com.acme.orchestrator.core.engine.Command.Halt;
import com.acme.orchestrator.core.engine.Command.Resume;
import com.acme.orchestrator.core.engine.Command.StageCompleted;
import com.acme.orchestrator.core.engine.Command.StageFailed;
import com.acme.orchestrator.core.engine.Command.Wake;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventDraft;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.Condition;
import com.acme.orchestrator.core.model.Executor;
import com.acme.orchestrator.core.model.GateCatalog;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs one workflow run. A single dedicated thread consumes {@link Command}s and owns every state
 * transition (ADR-0006); agents run in parallel on virtual threads and only report back through
 * commands. So state changes never race, the event order is deterministic for a given sequence of
 * results, and no locks are needed.
 *
 * <p>The live state is the projection of the run's own event log (re-projected after each event),
 * so what the coordinator acts on is exactly what crash recovery would rebuild.
 *
 * <p>Each pass: every PENDING stage whose dependencies are SUCCEEDED/SKIPPED becomes READY; a false
 * condition skips it (which satisfies dependents); entry gates are evaluated; it is dispatched.
 * Joins need no special handling: a stage is ready only when all its dependencies are. When an
 * agent finishes, its artifacts are stored, exit gates evaluated, and the stage succeeds, waits for
 * approval, or fails.
 *
 * <p>Not yet (later steps): retries, fallback and loop-back (4.4), the full approval rules (4.3),
 * re-planning (4.5), recovery of attempts in flight at a crash (4.4), task expansion (Phase 5).
 * Until then any failure stops new dispatches and fails the run once in-flight work has finished.
 */
public final class Coordinator implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(Coordinator.class.getName());

  /** What the coordinator works with. */
  public record Services(
      EventStore events,
      ArtifactStore artifacts,
      AgentRegistry agents,
      GateEvaluator gates,
      ToolRegistry tools) {

    public Services {
      Objects.requireNonNull(events, "events");
      Objects.requireNonNull(artifacts, "artifacts");
      Objects.requireNonNull(agents, "agents");
      Objects.requireNonNull(gates, "gates");
      Objects.requireNonNull(tools, "tools");
    }

    public static Services of(EventStore events, ArtifactStore artifacts, AgentRegistry agents) {
      return new Services(events, artifacts, agents, GateEvaluator.PASS_ALL, ToolRegistry.EMPTY);
    }
  }

  private final WorkflowDefinition workflow;
  private final String runId;
  private final Services services;
  private final Dag<String> dag;
  private final Map<String, StageDefinition> stages;
  private final Map<String, Condition> conditions = new LinkedHashMap<>();
  private final BlockingQueue<Command> inbox = new LinkedBlockingQueue<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Thread loop;
  private final Object monitor = new Object();

  /** Coordinator thread only. */
  private RunState current;

  /** Stage key → seq of the STAGE_STARTED of its attempt in flight. Coordinator thread only. */
  private final Map<String, Long> inFlight = new LinkedHashMap<>();

  private boolean broken;

  /** Published after each command is fully handled; read by any thread. */
  private volatile RunState published;

  private volatile boolean closed;

  private Coordinator(WorkflowDefinition workflow, Run run, Services services) {
    this.workflow = workflow;
    this.runId = run.id();
    this.services = services;
    this.dag = WorkflowGraph.of(workflow);
    this.stages = workflow.stagesById();
    workflow
        .stages()
        .forEach(s -> s.condition().ifPresent(c -> conditions.put(s.id(), Condition.parse(c))));
    this.loop = Thread.ofPlatform().name("coordinator-" + runId).daemon().unstarted(this::runLoop);
  }

  /**
   * Starts (or continues) {@code run}: appends RUN_CREATED if its log is empty, then schedules.
   *
   * @throws IllegalArgumentException if the run isn't of this workflow, an executor has no agent,
   *     or (when the gate evaluator is a {@link GateCatalog}) a gate is unknown
   */
  public static Coordinator start(WorkflowDefinition workflow, Run run, Services services) {
    if (!run.workflowId().equals(workflow.id())) {
      throw new IllegalArgumentException(
          "run " + run.id() + " is of workflow " + run.workflowId() + ", not " + workflow.id());
    }
    List<String> missing = services.agents().missing(workflow);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("no agent registered for: " + String.join(", ", missing));
    }
    if (services.gates() instanceof GateCatalog catalog) {
      for (StageDefinition stage : workflow.stages()) {
        for (GateRef gate :
            Stream.concat(stage.entryGates().stream(), stage.exitGates().stream()).toList()) {
          try {
            catalog.check(gate);
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "stage " + stage.id() + " gate " + gate + ": " + e.getMessage(), e);
          }
        }
      }
    }
    Coordinator c = new Coordinator(workflow, run, services);
    c.begin(run);
    return c;
  }

  private void begin(Run run) {
    if (services.events().head(runId).isEmpty()) {
      Map<String, Object> payload = new LinkedHashMap<>(RunStateProjector.runCreatedPayload(run));
      payload.put("workflowVersion", workflow.version());
      services.events().append(EventDraft.run(runId, RUN_CREATED, Actor.SYSTEM, payload));
    }
    refresh();
    publish();
    loop.start();
    inbox.add(new Wake());
  }

  public String runId() {
    return runId;
  }

  /** Thread-safe. Commands are handled in arrival order on the coordinator thread. */
  public void submit(Command command) {
    if (closed) {
      throw new IllegalStateException("coordinator for run " + runId + " is closed");
    }
    inbox.add(Objects.requireNonNull(command, "command"));
  }

  /** The latest consistent state (after the last fully handled command). */
  public RunState state() {
    return published;
  }

  /** Blocks until {@code until} holds for the published state. */
  public RunState await(Predicate<RunState> until, Duration timeout)
      throws InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (monitor) {
      while (!until.test(published)) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          throw new TimeoutException(
              "run "
                  + runId
                  + " did not reach the expected state; it is "
                  + published.run().status());
        }
        TimeUnit.NANOSECONDS.timedWait(monitor, left);
      }
      return published;
    }
  }

  /** Waits until the run finishes, halts or needs a human. */
  public RunState awaitSettled(Duration timeout) throws InterruptedException, TimeoutException {
    return await(
        s -> {
          RunStatus status = s.run().status();
          return status.isTerminal()
              || status == RunStatus.HALTED
              || status == RunStatus.AWAITING_HUMAN;
        },
        timeout);
  }

  /** Stops the loop and cancels agents still running. The log stays as it is. */
  @Override
  public void close() {
    closed = true;
    loop.interrupt();
    workers.shutdownNow();
    try {
      loop.join(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---- the loop -----------------------------------------------------------------------------

  private void runLoop() {
    while (!closed) {
      Command command;
      try {
        command = inbox.take();
      } catch (InterruptedException e) {
        return;
      }
      if (broken) {
        continue;
      }
      try {
        handle(command);
        schedule();
      } catch (RuntimeException e) {
        broken = true; // a bug or a corrupt log: stop acting rather than guess
        LOG.log(System.Logger.Level.ERROR, "coordinator for run " + runId + " failed", e);
        failRunAfterInternalError(e);
      }
      publish();
    }
  }

  private void handle(Command command) {
    switch (command) {
      case Wake w -> {}
      case StageCompleted c -> onCompleted(c);
      case StageFailed f -> onFailed(f);
      case ApprovalDecision d -> onApproval(d);
      case AmendRequirement a -> runEvent(REPLAN_TRIGGERED, a.by(), Map.of("amendment", a.text()));
      case Halt h -> {
        RunStatus status = current.run().status();
        if (!status.isTerminal() && status != RunStatus.HALTED) {
          runEvent(RUN_HALTED, h.by(), Map.of("reason", h.reason()));
        }
      }
      case Resume r -> {
        if (current.run().status() == RunStatus.HALTED) {
          runEvent(RUN_RESUMED, r.by(), Map.of());
        }
      }
    }
  }

  /** Dispatches everything that can run, then checks for completion or failure. */
  private void schedule() {
    while (true) {
      RunStatus status = current.run().status();
      if (status.isTerminal() || status == RunStatus.HALTED) {
        return;
      }
      Optional<StageInstance> failed = firstWithStatus(StageStatus.FAILED);
      if (failed.isPresent()) {
        if (nothingInFlight()) {
          runEvent(
              RUN_FAILED,
              Actor.SYSTEM,
              Map.of(
                  "reason",
                  "stage " + failed.get().key() + " failed",
                  "stageId",
                  failed.get().key()));
        }
        return;
      }
      if (dag.nodes().stream().allMatch(id -> statusOf(id).satisfiesDependency())) {
        runEvent(RUN_COMPLETED, Actor.SYSTEM, Map.of());
        return;
      }
      List<String> ready = dag.readyNodes(current.statuses());
      if (ready.isEmpty()) {
        return; // waiting for agents or approvals
      }
      for (String stageId : ready) {
        prepare(stageId);
        if (firstWithStatus(StageStatus.FAILED).isPresent()) {
          break; // an entry gate failed: dispatch nothing more
        }
      }
      // loop again: skips may have made further stages ready
    }
  }

  private void prepare(String stageId) {
    StageDefinition def = stages.get(stageId);
    stageEvent(STAGE_READY, stageId, Actor.SYSTEM, Map.of());

    Condition condition = conditions.get(stageId);
    if (condition != null && !condition.test(current.run())) {
      stageEvent(STAGE_SKIPPED, stageId, Actor.SYSTEM, Map.of("condition", def.condition().get()));
      return;
    }
    Optional<String> entryFailure = evaluateGates(def, GateContext.Phase.ENTRY, List.of());
    if (entryFailure.isPresent()) {
      stageEvent(STAGE_FAILED, stageId, Actor.SYSTEM, Map.of("reason", entryFailure.get()));
      return;
    }
    dispatch(stageId, def);
  }

  private void dispatch(String stageId, StageDefinition def) {
    stageEvent(STAGE_STARTED, stageId, Actor.SYSTEM, Map.of());
    long dispatch = current.lastSeq();
    inFlight.put(stageId, dispatch);
    int attempt = current.stages().get(stageId).attempt();
    Executor executor = def.executor();
    Agent agent = services.agents().find(executor).orElseThrow(); // checked at start
    stageEvent(
        AGENT_INVOKED,
        stageId,
        actorOf(def),
        Map.of("executor", executor.name(), "attempt", attempt));

    StageContext context =
        new StageContext(
            current.run(),
            stageId,
            attempt,
            feedback(stageId),
            services.artifacts(),
            services.tools());
    Duration timeout = def.timeout();
    CompletableFuture.supplyAsync(() -> execute(agent, context), workers)
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((result, error) -> {
          if (!closed) {
            inbox.add(outcome(stageId, dispatch, context, timeout, result, error));
          }
        });
  }

  private void onCompleted(StageCompleted c) {
    if (!isCurrentAttempt(c.stageKey(), c.dispatch())) {
      return; // stale: the stage was halted or moved on while the agent ran
    }
    String stageId = c.stageKey();
    StageDefinition def = stages.get(stageId);
    List<Artifact> produced = new ArrayList<>();
    try {
      for (ArtifactOutput out : c.result().artifacts()) {
        Artifact a =
            services
                .artifacts()
                .save(
                    new ArtifactDraft(
                        runId,
                        stageId,
                        out.type(),
                        out.contentJson(),
                        new ProducedBy(def.executor().name(), out.model(), out.promptHash()),
                        c.reads(),
                        out.rationale()));
        produced.add(a);
        stageEvent(
            ARTIFACT_PRODUCED,
            stageId,
            actorOf(def),
            Map.of(
                "artifactId", a.id(),
                "type", a.type(),
                "version", a.version(),
                "contentHash", a.contentHash()));
      }
    } catch (IllegalArgumentException e) {
      stageEvent(
          STAGE_FAILED,
          stageId,
          Actor.SYSTEM,
          Map.of("reason", "could not store artifact: " + e.getMessage()));
      return;
    }

    Optional<String> exitFailure = evaluateGates(def, GateContext.Phase.EXIT, produced);
    if (exitFailure.isPresent()) {
      stageEvent(STAGE_FAILED, stageId, Actor.SYSTEM, Map.of("reason", exitFailure.get()));
    } else if (def.approval().required()) {
      stageEvent(
          APPROVAL_REQUESTED,
          stageId,
          Actor.SYSTEM,
          Map.of(
              "reason", def.approval().reason(),
              "summary", c.result().summary(),
              "artifacts", produced.stream().map(Artifact::id).toList()));
    } else {
      stageEvent(STAGE_SUCCEEDED, stageId, actorOf(def), Map.of("summary", c.result().summary()));
    }
  }

  private void onFailed(StageFailed f) {
    if (isCurrentAttempt(f.stageKey(), f.dispatch())) {
      stageEvent(
          STAGE_FAILED,
          f.stageKey(),
          actorOf(stages.get(f.stageKey())),
          Map.of("reason", f.reason()));
    }
  }

  private void onApproval(ApprovalDecision d) {
    StageInstance stage = current.stages().get(d.stageKey());
    if (stage == null || stage.status() != StageStatus.WAITING_APPROVAL) {
      LOG.log(System.Logger.Level.WARNING, "ignored approval for {0}: not waiting", d.stageKey());
      return;
    }
    if (!(d.approver() instanceof Actor.Human)) {
      // Only humans approve (role checks arrive in Step 4.3).
      LOG.log(System.Logger.Level.WARNING, "ignored approval by non-human {0}", d.approver());
      return;
    }
    EventType type =
        d.decision() == ApprovalDecision.Decision.APPROVE ? APPROVAL_GRANTED : APPROVAL_REJECTED;
    stageEvent(type, d.stageKey(), d.approver(), Map.of("comment", d.comment()));
  }

  // ---- helpers ------------------------------------------------------------------------------

  /** Evaluates every gate (each emits GATE_EVALUATED); returns the failure reasons, if any. */
  private Optional<String> evaluateGates(
      StageDefinition def, GateContext.Phase phase, List<Artifact> produced) {
    List<GateRef> gates = phase == GateContext.Phase.ENTRY ? def.entryGates() : def.exitGates();
    GateContext context =
        new GateContext(
            current.run(),
            def,
            phase,
            produced,
            type -> services.artifacts().latestOfType(runId, type));
    List<String> failures = new ArrayList<>();
    for (GateRef gate : gates) {
      GateResult result;
      try {
        result = services.gates().evaluate(gate, context);
      } catch (RuntimeException e) {
        result = GateResult.fail("gate error: " + e);
      }
      stageEvent(
          GATE_EVALUATED,
          def.id(),
          Actor.SYSTEM,
          Map.of(
              "gate", gate.toString(),
              "phase", phase,
              "passed", result.passed(),
              "reasons", result.reasons()));
      if (!result.passed()) {
        failures.add(
            phase.name().toLowerCase(Locale.ROOT)
                + " gate "
                + gate
                + " failed: "
                + String.join("; ", result.reasons()));
      }
    }
    return failures.isEmpty() ? Optional.empty() : Optional.of(String.join(" | ", failures));
  }

  /** Runs on a worker thread. */
  private static AgentResult execute(Agent agent, StageContext context) {
    try {
      return agent.execute(context);
    } catch (Exception e) {
      throw new CompletionException(e);
    }
  }

  private static Command outcome(
      String stageId,
      long dispatch,
      StageContext context,
      Duration timeout,
      AgentResult result,
      Throwable error) {
    if (error != null) {
      Throwable cause =
          error instanceof CompletionException && error.getCause() != null
              ? error.getCause()
              : error;
      String reason =
          cause instanceof TimeoutException
              ? "timed out after " + timeout
              : "agent threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
      return new StageFailed(stageId, dispatch, reason);
    }
    return switch (result) {
      case null -> new StageFailed(stageId, dispatch, "agent returned no result");
      case AgentResult.Success s -> new StageCompleted(stageId, dispatch, s, context.reads());
      case AgentResult.Failure f -> new StageFailed(stageId, dispatch, f.reason());
    };
  }

  /** Failure reasons and rejection comments of earlier attempts, oldest first. */
  private List<String> feedback(String stageKey) {
    List<String> feedback = new ArrayList<>();
    for (Event e : services.events().events(runId)) {
      if (e.stageId().filter(stageKey::equals).isEmpty()) {
        continue;
      }
      if (e.type() == STAGE_FAILED) {
        e.payloadString("reason").ifPresent(feedback::add);
      } else if (e.type() == APPROVAL_REJECTED) {
        e.payloadString("comment").ifPresent(c -> feedback.add("rejected: " + c));
      }
    }
    return feedback;
  }

  /** The result belongs to the attempt in flight; consumes it, so it can't be applied twice. */
  private boolean isCurrentAttempt(String stageKey, long dispatch) {
    StageInstance s = current.stages().get(stageKey);
    boolean matches =
        s != null
            && s.status() == StageStatus.RUNNING
            && Long.valueOf(dispatch).equals(inFlight.get(stageKey));
    if (matches) {
      inFlight.remove(stageKey);
    }
    return matches;
  }

  private boolean nothingInFlight() {
    return current.stages().values().stream()
        .noneMatch(s ->
            s.status() == StageStatus.RUNNING
                || s.status() == StageStatus.READY
                || s.status() == StageStatus.RETRYING);
  }

  private Optional<StageInstance> firstWithStatus(StageStatus status) {
    return current.stages().values().stream().filter(s -> s.status() == status).findFirst();
  }

  private StageStatus statusOf(String stageKey) {
    StageInstance s = current.stages().get(stageKey);
    return s == null ? StageStatus.PENDING : s.status();
  }

  private static Actor actorOf(StageDefinition def) {
    return def.executor() instanceof Executor.Agent a ? Actor.agent(a.name()) : Actor.SYSTEM;
  }

  private void stageEvent(EventType type, String stageKey, Actor actor, Map<String, ?> payload) {
    services.events().append(EventDraft.stage(runId, type, stageKey, actor, payload));
    refresh();
  }

  private void runEvent(EventType type, Actor actor, Map<String, ?> payload) {
    services.events().append(EventDraft.run(runId, type, actor, payload));
    refresh();
  }

  private void refresh() {
    current = RunStateProjector.project(services.events().events(runId));
  }

  private void publish() {
    synchronized (monitor) {
      published = current;
      monitor.notifyAll();
    }
  }

  private void failRunAfterInternalError(RuntimeException e) {
    try {
      refresh();
      if (!current.run().status().isTerminal()) {
        runEvent(RUN_FAILED, Actor.SYSTEM, Map.of("reason", "coordinator error: " + e));
      }
    } catch (RuntimeException again) {
      LOG.log(System.Logger.Level.ERROR, "could not record the failure of run " + runId, again);
    }
  }
}
