package com.acme.orchestrator.core.engine;

import static com.acme.orchestrator.core.event.EventType.AGENT_INVOKED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_GRANTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REJECTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REQUESTED;
import static com.acme.orchestrator.core.event.EventType.ARTIFACT_PRODUCED;
import static com.acme.orchestrator.core.event.EventType.BUDGET_EXCEEDED;
import static com.acme.orchestrator.core.event.EventType.CHECKPOINT_CREATED;
import static com.acme.orchestrator.core.event.EventType.FALLBACK_USED;
import static com.acme.orchestrator.core.event.EventType.GATE_EVALUATED;
import static com.acme.orchestrator.core.event.EventType.POLICY_EVALUATED;
import static com.acme.orchestrator.core.event.EventType.REPLAN_TRIGGERED;
import static com.acme.orchestrator.core.event.EventType.ROLLED_BACK;
import static com.acme.orchestrator.core.event.EventType.RUN_COMPLETED;
import static com.acme.orchestrator.core.event.EventType.RUN_CREATED;
import static com.acme.orchestrator.core.event.EventType.RUN_FAILED;
import static com.acme.orchestrator.core.event.EventType.RUN_HALTED;
import static com.acme.orchestrator.core.event.EventType.RUN_RESUMED;
import static com.acme.orchestrator.core.event.EventType.SAFE_STOP_REQUESTED;
import static com.acme.orchestrator.core.event.EventType.STAGE_FAILED;
import static com.acme.orchestrator.core.event.EventType.STAGE_INVALIDATED;
import static com.acme.orchestrator.core.event.EventType.STAGE_READY;
import static com.acme.orchestrator.core.event.EventType.STAGE_RETRIED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SKIPPED;
import static com.acme.orchestrator.core.event.EventType.STAGE_STARTED;
import static com.acme.orchestrator.core.event.EventType.STAGE_SUCCEEDED;
import static com.acme.orchestrator.core.event.EventType.USAGE_RECORDED;
import static com.acme.orchestrator.core.event.RunStateProjector.AUTONOMY_HASH;
import static com.acme.orchestrator.core.event.RunStateProjector.POLICIES_HASH;

import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.budget.BudgetGuard;
import com.acme.orchestrator.core.budget.Usage;
import com.acme.orchestrator.core.checkpoint.CheckpointManager;
import com.acme.orchestrator.core.checkpoint.CheckpointRef;
import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.dag.WorkflowGraph;
import com.acme.orchestrator.core.engine.Command.AmendRequirement;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision;
import com.acme.orchestrator.core.engine.Command.ApprovalTimeout;
import com.acme.orchestrator.core.engine.Command.BudgetCheck;
import com.acme.orchestrator.core.engine.Command.GraceExpired;
import com.acme.orchestrator.core.engine.Command.Halt;
import com.acme.orchestrator.core.engine.Command.Kill;
import com.acme.orchestrator.core.engine.Command.Resume;
import com.acme.orchestrator.core.engine.Command.RetryDue;
import com.acme.orchestrator.core.engine.Command.Rollback;
import com.acme.orchestrator.core.engine.Command.StageCompleted;
import com.acme.orchestrator.core.engine.Command.StageFailed;
import com.acme.orchestrator.core.engine.Command.Wake;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventDraft;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.CheckpointKind;
import com.acme.orchestrator.core.model.Condition;
import com.acme.orchestrator.core.model.Executor;
import com.acme.orchestrator.core.model.GateCatalog;
import com.acme.orchestrator.core.model.GateRef;
import com.acme.orchestrator.core.model.OnFailure;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.StageEvent;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.StageStateMachine;
import com.acme.orchestrator.core.model.StageStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.policy.Effect;
import com.acme.orchestrator.core.policy.Invariant;
import com.acme.orchestrator.core.policy.PolicyEngine;
import com.acme.orchestrator.core.policy.PolicyMatch;
import com.acme.orchestrator.core.policy.PolicySet;
import com.acme.orchestrator.core.policy.ProposedAction;
import com.acme.orchestrator.core.replan.Replanner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.IntStream;
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
 * <p>Failures are retried, looped back or fallen back (see {@link #failAttempt}); stages with
 * {@code checkpoint} get a checkpoint before they start, restored when an attempt that wrote files
 * fails and on rollback. A safe stop (halt, kill switch, budget, approval timeout, high-impact
 * failure, repeated policy DENY) dispatches nothing new, lets running attempts finish within the
 * grace period, checkpoints and halts. Starting a coordinator on an existing log recovers: attempts
 * that were running are retried, timers are re-armed.
 *
 * <p>Re-planning (see {@link Replanner}): an amended requirement, a rejection, or a re-run that
 * changed an artifact invalidates what depends on it; approvals of invalidated stages are voided.
 *
 * <p>Not yet: task expansion (Phase 5).
 */
public final class Coordinator implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(Coordinator.class.getName());

  /** Recorded when someone without authority tries to approve (see {@link Invariant}). */
  static final String HUMAN_APPROVER_POLICY = "INV-human-approver";

  /** How long a safe stop waits for running attempts before cancelling them. */
  public static final Duration DEFAULT_SAFE_STOP_GRACE = Duration.ofSeconds(30);

  /** Policy DENYs (since the last resume) that make the run safe-stop. */
  static final int MAX_DENIALS = 3;

  /**
   * What the coordinator works with.
   *
   * @param policies loaded once at run start; their hashes are recorded in RUN_CREATED
   * @param checkpoints saves and restores the run's workspace
   * @param safeStopGrace how long a safe stop lets running attempts finish
   */
  public record Services(
      EventStore events,
      ArtifactStore artifacts,
      AgentRegistry agents,
      GateEvaluator gates,
      ToolRegistry tools,
      PolicyEngine policies,
      CheckpointManager checkpoints,
      Duration safeStopGrace) {

    public Services {
      Objects.requireNonNull(events, "events");
      Objects.requireNonNull(artifacts, "artifacts");
      Objects.requireNonNull(agents, "agents");
      Objects.requireNonNull(gates, "gates");
      Objects.requireNonNull(tools, "tools");
      Objects.requireNonNull(policies, "policies");
      Objects.requireNonNull(checkpoints, "checkpoints");
      if (safeStopGrace == null || safeStopGrace.isNegative()) {
        throw new IllegalArgumentException("safeStopGrace must be zero or more");
      }
    }

    /** No checkpoints and the default grace period. */
    public Services(
        EventStore events,
        ArtifactStore artifacts,
        AgentRegistry agents,
        GateEvaluator gates,
        ToolRegistry tools,
        PolicyEngine policies) {
      this(
          events,
          artifacts,
          agents,
          gates,
          tools,
          policies,
          CheckpointManager.NONE,
          DEFAULT_SAFE_STOP_GRACE);
    }

    public Services withCheckpoints(CheckpointManager manager) {
      return new Services(
          events, artifacts, agents, gates, tools, policies, manager, safeStopGrace);
    }

    public Services withSafeStopGrace(Duration grace) {
      return new Services(events, artifacts, agents, gates, tools, policies, checkpoints, grace);
    }

    /** Without config policies (the invariants still apply). */
    public Services(
        EventStore events,
        ArtifactStore artifacts,
        AgentRegistry agents,
        GateEvaluator gates,
        ToolRegistry tools) {
      this(events, artifacts, agents, gates, tools, PolicyEngine.withoutPolicies());
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

  /** Stage key → seq of its open APPROVAL_REQUESTED (for the timeout). Coordinator thread only. */
  private final Map<String, Long> approvalRequests = new LinkedHashMap<>();

  /** Stage key → seq of its STAGE_RETRIED awaiting the backoff. Coordinator thread only. */
  private final Map<String, Long> retryTimers = new LinkedHashMap<>();

  /** Stage key → the worker running its attempt (cancelled on kill or after the grace). */
  private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

  /** Set while a safe stop waits for running attempts. Coordinator thread only. */
  private SafeStop stopping;

  private record SafeStop(String reason, Actor by, long request, List<String> notes) {}

  private BudgetGuard budget;

  private Instant createdAt;

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
    PolicySet policies = services.policies().policies();
    if (services.events().head(runId).isEmpty()) {
      Map<String, Object> payload = new LinkedHashMap<>(RunStateProjector.runCreatedPayload(run));
      payload.put("workflowVersion", workflow.version());
      payload.put(POLICIES_HASH, policies.policiesHash());
      payload.put(AUTONOMY_HASH, policies.autonomyHash());
      services.events().append(EventDraft.run(runId, RUN_CREATED, Actor.SYSTEM, payload));
    } else {
      // change control: a run continues only under the rules it started with
      Map<String, Object> created = services.events().events(runId).getFirst().payload();
      if (!policies.policiesHash().equals(created.get(POLICIES_HASH))
          || !policies.autonomyHash().equals(created.get(AUTONOMY_HASH))) {
        throw new IllegalStateException(
            "run "
                + runId
                + " started under different policies (policies.yml/autonomy.yml changed);"
                + " it can't continue under the new ones");
      }
    }
    refresh();
    createdAt = services.events().events(runId).getFirst().timestamp();
    budget = new BudgetGuard(current.run().budgets(), usedSoFar());
    recover();
    publish();
    loop.start();
    later(budget.wallClockLeft(elapsed()), new BudgetCheck());
    inbox.add(new Wake());
  }

  /**
   * Continues a run whose coordinator stopped (crash or restart): attempts that were running are
   * lost, so they count as transient failures (retried if attempts remain); pending retries,
   * approval timeouts and an unfinished safe stop are picked up again.
   */
  private void recover() {
    List<Event> log = services.events().events(runId);
    // a safe stop that was requested but never finished: it finishes now (nothing is running)
    for (int i = log.size() - 1; i >= 0; i--) {
      Event e = log.get(i);
      if (e.type() == RUN_HALTED || e.type() == RUN_RESUMED) {
        break;
      }
      if (e.type() == SAFE_STOP_REQUESTED) {
        stopping =
            new SafeStop(
                e.payloadString("reason").orElse("safe stop"),
                e.actor(),
                e.seq(),
                new ArrayList<>());
        break;
      }
    }
    for (StageInstance stage : List.copyOf(current.stages().values())) {
      String key = stage.key();
      switch (stage.status()) {
        case RUNNING ->
            failAttempt(
                key,
                "interrupted: the coordinator stopped while this attempt was running",
                FailureKind.TRANSIENT,
                Actor.SYSTEM);
        case RETRYING -> {
          long retry = stageEvents(key).getLast().seq();
          retryTimers.put(key, retry);
          later(Duration.ZERO, new RetryDue(key, retry));
        }
        case WAITING_APPROVAL ->
            stageEvents(key).stream()
                .filter(e -> e.type() == APPROVAL_REQUESTED)
                .reduce((first, second) -> second)
                .ifPresent(request -> {
                  approvalRequests.put(key, request.seq());
                  stages
                      .get(key)
                      .approval()
                      .timeout()
                      .ifPresent(t -> {
                        Duration waited =
                            Duration.between(
                                request.timestamp(), services.events().clock().instant());
                        Duration left = t.minus(waited);
                        later(
                            left.isNegative() ? Duration.ZERO : left,
                            new ApprovalTimeout(key, request.seq()));
                      });
                });
        default -> {}
      }
    }
  }

  private Usage usedSoFar() {
    Usage used = Usage.NONE;
    for (Event e : services.events().events(runId)) {
      if (e.type() == USAGE_RECORDED) {
        used =
            used.plus(
                new Usage(
                    ((Number) e.payload().get("llmCalls")).longValue(),
                    ((Number) e.payload().get("tokens")).longValue()));
      }
    }
    return used;
  }

  private Duration elapsed() {
    Duration d = Duration.between(createdAt, services.events().clock().instant());
    return d.isNegative() ? Duration.ZERO : d;
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
        checkBudgets();
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
      case ApprovalTimeout t -> onApprovalTimeout(t);
      case RetryDue r -> onRetryDue(r);
      case AmendRequirement a -> onAmend(a);
      case Halt h -> requestSafeStop(h.reason(), h.by(), false, Map.of());
      case Kill k -> requestSafeStop(k.reason(), k.by(), true, Map.of());
      case GraceExpired g -> {
        if (stopping != null && stopping.request() == g.request()) {
          finishSafeStop();
        }
      }
      case BudgetCheck b -> {} // checkBudgets() runs after every command
      case Rollback r -> onRollback(r);
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
      if (stopping != null) {
        if (inFlight.isEmpty()) {
          finishSafeStop();
        }
        return; // a safe stop dispatches nothing new
      }
      List<String> ready = dag.readyNodes(schedulable());
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
      // the inputs are what they are: retrying the stage can't help
      failAttempt(stageId, entryFailure.get(), FailureKind.FATAL, Actor.SYSTEM);
      return;
    }
    if (def.checkpoint() != CheckpointKind.NONE) {
      try {
        CheckpointRef ref = services.checkpoints().create(runId, stageId);
        stageEvent(
            CHECKPOINT_CREATED,
            stageId,
            Actor.SYSTEM,
            Map.of("checkpointId", ref.id(), "before", stageId));
      } catch (RuntimeException e) {
        failAttempt(stageId, "could not create checkpoint: " + e, FailureKind.FATAL, Actor.SYSTEM);
        return;
      }
    }
    if (approvedBeforeRunning(def)) {
      requestApprovalToStart(def);
      return;
    }
    dispatch(stageId, def);
  }

  /**
   * Publish actions (merging to main) are approved before they run, not after: an approval of a
   * merge that already happened would govern nothing.
   */
  private static boolean approvedBeforeRunning(StageDefinition def) {
    return def.executor() instanceof Executor.Action action
        && PolicyEngine.PUBLISH_ACTIONS.contains(action.name());
  }

  /** READY → WAITING_APPROVAL, showing the approver the release and validation reports. */
  private void requestApprovalToStart(StageDefinition def) {
    List<Artifact> evidence =
        List.of(ArtifactTypes.RELEASE_REPORT, ArtifactTypes.VALIDATION_REPORT).stream()
            .map(type -> services.artifacts().latestOfType(runId, type))
            .flatMap(Optional::stream)
            .toList();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(
        "reason",
        def.approval().required()
            ? def.approval().reason()
            : Invariant.PUBLISH_REQUIRES_APPROVAL.text());
    payload.put("summary", def.executor().name() + " runs once this is approved");
    payload.put("before", true);
    payload.put("artifacts", evidence.stream().map(Artifact::id).toList());
    payload.put("artifactRefs", evidence.stream().map(Coordinator::refPayload).toList());
    def.approval().timeout().ifPresent(t -> payload.put("timeout", t));
    stageEvent(APPROVAL_REQUESTED, def.id(), Actor.SYSTEM, payload);

    long request = current.lastSeq();
    approvalRequests.put(def.id(), request);
    def.approval().timeout().ifPresent(t -> later(t, new ApprovalTimeout(def.id(), request)));
  }

  private void dispatch(String stageId, StageDefinition def) {
    dispatch(stageId, def, Optional.empty());
  }

  /** Starts an attempt; {@code fallback} is the strategy when it is the fallback attempt. */
  private void dispatch(String stageId, StageDefinition def, Optional<String> fallback) {
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
        fallback
            .<Map<String, Object>>map(
                f -> Map.of("executor", executor.name(), "attempt", attempt, "fallback", f))
            .orElseGet(() -> Map.of("executor", executor.name(), "attempt", attempt)));

    StageContext context =
        new StageContext(
            current.run(),
            stageId,
            attempt,
            feedback(stageId),
            services.artifacts(),
            services.tools(),
            services.policies(),
            fallback,
            budget);
    Duration timeout = def.timeout();
    CompletableFuture<AgentResult> result = new CompletableFuture<>();
    Future<?> task =
        workers.submit(() -> {
          try {
            result.complete(agent.execute(context));
          } catch (Throwable t) {
            result.completeExceptionally(t);
          }
        });
    running.put(stageId, task);
    result
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .whenComplete((r, error) -> {
          task.cancel(true); // a timed-out agent is interrupted, not left running
          running.remove(stageId, task);
          if (!closed) {
            inbox.add(outcome(stageId, dispatch, context, timeout, r, error));
          }
        });
  }

  private void onCompleted(StageCompleted c) {
    recordUsage(c.stageKey(), c.usage());
    recordChecks(c.stageKey(), c.checks());
    checkDenials(c.checks());
    if (!isCurrentAttempt(c.stageKey(), c.dispatch())) {
      return; // stale: the stage was halted or moved on while the agent ran
    }
    String stageId = c.stageKey();
    StageDefinition def = stages.get(stageId);
    List<String> denied =
        c.checks().stream()
            .filter(check -> check.decision().denied())
            .map(check -> check.describe() + " (" + policyIds(check, Effect.DENY) + ")")
            .toList();
    if (!denied.isEmpty()) {
      failAttempt(
          stageId, "policy denied: " + String.join("; ", denied), FailureKind.FATAL, Actor.SYSTEM);
      return;
    }

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
      failAttempt(
          stageId, "could not store artifact: " + e.getMessage(), FailureKind.FATAL, Actor.SYSTEM);
      return;
    }

    replanIfOutputChanged(stageId, produced);

    Optional<String> exitFailure = evaluateGates(def, GateContext.Phase.EXIT, produced);
    if (exitFailure.isPresent()) {
      failAttempt(stageId, exitFailure.get(), FailureKind.VALIDATION, Actor.SYSTEM);
      return;
    }
    OptionalInt risk = stageRisk(c);
    List<String> reasons = new ArrayList<>();
    if (!approvedBeforeRunning(def)
        && services.policies().stageApprovalRequired(def, current.run().autonomyLevel(), risk)) {
      reasons.add(
          def.approval().required()
              ? def.approval().reason()
              : Invariant.PUBLISH_REQUIRES_APPROVAL.text());
    }
    c.checks().stream()
        .filter(check -> check.decision().effect() == Effect.REQUIRE_APPROVAL)
        .forEach(check ->
            reasons.add(
                "policy: "
                    + check.describe()
                    + " needs approval ("
                    + policyIds(check, Effect.REQUIRE_APPROVAL)
                    + ")"));
    if (reasons.isEmpty()) {
      stageEvent(STAGE_SUCCEEDED, stageId, actorOf(def), Map.of("summary", c.result().summary()));
    } else {
      requestApproval(def, reasons, c, produced, risk);
    }
  }

  /**
   * Stage → WAITING_APPROVAL (run → AWAITING_HUMAN once nothing else can run), with what an
   * approver needs: the reasons, the produced artifact versions, risk, matched policies and diff
   * size. Starts the approval timeout, if one is configured.
   */
  private void requestApproval(
      StageDefinition def,
      List<String> reasons,
      StageCompleted c,
      List<Artifact> produced,
      OptionalInt risk) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", String.join("; ", reasons));
    payload.put("summary", c.result().summary());
    payload.put("artifacts", produced.stream().map(Artifact::id).toList());
    payload.put(
        "artifactRefs",
        produced.stream()
            .map(a ->
                Map.of(
                    "id", a.id(),
                    "type", a.type(),
                    "version", a.version(),
                    "contentHash", a.contentHash()))
            .toList());
    risk.ifPresent(r -> payload.put("riskScore", r));
    Map<String, Map<String, Object>> matched = new LinkedHashMap<>();
    for (PolicyCheck check : c.checks()) {
      for (PolicyMatch m : check.decision().matchedPolicies()) {
        matched.putIfAbsent(
            m.policyId(),
            Map.of("policyId", m.policyId(), "effect", m.effect(), "reason", m.reason()));
      }
    }
    payload.put("matchedPolicies", List.copyOf(matched.values()));
    c.checks().stream()
        .map(PolicyCheck::action)
        .filter(a -> a instanceof ProposedAction.Diff)
        .map(a -> (ProposedAction.Diff) a)
        .reduce((first, second) -> second) // the latest diff covers the attempt
        .ifPresent(d ->
            payload.put(
                "diffStats",
                Map.of("filesChanged", d.filesChanged(), "linesChanged", d.linesChanged())));
    def.approval().timeout().ifPresent(t -> payload.put("timeout", t));
    stageEvent(APPROVAL_REQUESTED, def.id(), Actor.SYSTEM, payload);

    long request = current.lastSeq();
    approvalRequests.put(def.id(), request);
    def.approval().timeout().ifPresent(t -> later(t, new ApprovalTimeout(def.id(), request)));
  }

  private void onFailed(StageFailed f) {
    recordUsage(f.stageKey(), f.usage());
    recordChecks(f.stageKey(), f.checks());
    checkDenials(f.checks());
    if (!isCurrentAttempt(f.stageKey(), f.dispatch())) {
      return;
    }
    if (f.kind() == FailureKind.BUDGET) {
      // not the stage's fault: stop the run; the stage re-runs on resume (if the budget allows)
      runEvent(
          BUDGET_EXCEEDED, Actor.SYSTEM, Map.of("reason", f.reason(), "stageId", f.stageKey()));
      requestSafeStop(f.reason(), Actor.SYSTEM, false, Map.of());
      return;
    }
    failAttempt(f.stageKey(), f.reason(), f.kind(), actorOf(stages.get(f.stageKey())));
  }

  private void onApproval(ApprovalDecision d) {
    StageInstance stage = current.stages().get(d.stageKey());
    if (stage == null || stage.status() != StageStatus.WAITING_APPROVAL) {
      LOG.log(System.Logger.Level.WARNING, "ignored approval for {0}: not waiting", d.stageKey());
      return;
    }
    String refusal =
        !(d.approver() instanceof Actor.Human)
            ? "only a human can approve, not " + d.approver()
            : !ApprovalDecision.APPROVER.equals(d.role())
                ? d.approver() + " has role '" + d.role() + "', not " + ApprovalDecision.APPROVER
                : null;
    if (refusal != null) {
      // recorded: an attempt to approve without authority is security-relevant
      stageEvent(
          POLICY_EVALUATED,
          d.stageKey(),
          d.approver(),
          Map.of(
              "action",
              "approval " + d.decision().name().toLowerCase(Locale.ROOT),
              "effect",
              Effect.DENY,
              "matchedPolicies",
              List.of(
                  Map.of(
                      "policyId",
                      HUMAN_APPROVER_POLICY,
                      "effect",
                      Effect.DENY,
                      "reason",
                      Invariant.HUMAN_APPROVER.text() + ": " + refusal))));
      return;
    }
    approvalRequests.remove(d.stageKey());
    Map<String, Object> payload = Map.of("comment", d.comment(), "role", d.role());
    boolean beforeRunning = stage.attempt() == 0;
    if (d.decision() == ApprovalDecision.Decision.APPROVE) {
      stageEvent(APPROVAL_GRANTED, d.stageKey(), d.approver(), payload);
      if (beforeRunning && stopping == null) {
        dispatch(d.stageKey(), stages.get(d.stageKey())); // READY again: it may run now
      }
      return;
    }

    stageEvent(APPROVAL_REJECTED, d.stageKey(), d.approver(), payload); // → RETRYING
    if (beforeRunning) {
      // a no-go: the action must never run
      failAttempt(
          d.stageKey(),
          "rejected by " + d.approver() + " before it ran: " + d.comment(),
          FailureKind.FATAL,
          Actor.SYSTEM);
      return;
    }
    restoreIfWritten(d.stageKey()); // the rejected work is discarded
    replan(
        "rejected",
        List.of(d.stageKey()),
        false,
        "approval of " + d.stageKey() + " was rejected",
        d.approver(),
        Map.of("comment", d.comment()),
        false,
        Optional.empty());
    StageDefinition def = stages.get(d.stageKey());
    int used = current.stages().get(d.stageKey()).attempt();
    int allowed = def.retry().maxAttempts();
    if (stopping != null) {
      stopping.notes().add(d.stageKey() + " was rejected; it re-runs on resume");
    } else if (used < allowed) {
      dispatch(d.stageKey(), def); // the comment reaches the agent through feedback()
    } else {
      failAttempt(
          d.stageKey(),
          "rejected by "
              + d.approver()
              + " and no attempts are left ("
              + used
              + " of "
              + allowed
              + " used): "
              + d.comment(),
          FailureKind.FATAL, // a human said no: no automatic second guess
          Actor.SYSTEM);
    }
  }

  /** Halts the run if the stage is still waiting on the request the timer was started for. */
  private void onApprovalTimeout(ApprovalTimeout t) {
    if (statusOf(t.stageKey()) == StageStatus.WAITING_APPROVAL
        && Long.valueOf(t.request()).equals(approvalRequests.get(t.stageKey()))
        && current.run().status() != RunStatus.HALTED) {
      approvalRequests.remove(t.stageKey());
      requestSafeStop(
          "approval of "
              + t.stageKey()
              + " was not given within "
              + stages.get(t.stageKey()).approval().timeout().orElseThrow(),
          Actor.SYSTEM,
          false,
          Map.of("stageId", t.stageKey()));
    }
  }

  // ---- re-planning ----------------------------------------------------------------------------

  /**
   * Trigger (a): stores the amended requirement as a new {@code requirement} version (derived from
   * the previous one, so both stay in the lineage) and re-runs the stages that read it, plus
   * everything downstream. If nothing has read it yet, the workflow's first stages re-run.
   */
  private void onAmend(AmendRequirement a) {
    if (current.run().status().isTerminal()) {
      LOG.log(System.Logger.Level.WARNING, "ignored amendment: run {0} is finished", runId);
      return;
    }
    Optional<Artifact> previous =
        services.artifacts().latestOfType(runId, ArtifactTypes.REQUIREMENT);
    Artifact amended =
        services
            .artifacts()
            .save(
                new ArtifactDraft(
                    runId,
                    ArtifactTypes.INPUT_STAGE,
                    ArtifactTypes.REQUIREMENT,
                    CanonicalJson.write(Map.of("text", a.text())),
                    ProducedBy.deterministic(a.by().toString()),
                    previous.map(Artifact::ref).stream().toList(),
                    "amended requirement"));
    runEvent(ARTIFACT_PRODUCED, a.by(), refPayload(amended));

    Set<String> readers = new LinkedHashSet<>();
    for (Artifact artifact : services.artifacts().byRun(runId)) {
      if (stages.containsKey(artifact.stageId())
          && artifact.inputs().stream().anyMatch(r -> r.type().equals(ArtifactTypes.REQUIREMENT))) {
        readers.add(artifact.stageId());
      }
    }
    if (readers.isEmpty()) {
      dag.nodes().stream().filter(n -> dag.dependenciesOf(n).isEmpty()).forEach(readers::add);
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("amendment", a.text());
    previous.ifPresent(p -> details.put("before", refPayload(p)));
    details.put("after", refPayload(amended));
    replan(
        "amendment",
        readers,
        true,
        "the requirement was amended",
        a.by(),
        details,
        true,
        Optional.of("requirement amended by " + a.by() + ": " + a.text()));
  }

  /**
   * Trigger (c): a re-run produced different content than the version downstream stages used, so
   * they must run again. (No rollback: that would undo what this stage just wrote.)
   */
  private void replanIfOutputChanged(String stageId, List<Artifact> produced) {
    List<Map<String, Object>> changes = new ArrayList<>();
    for (Artifact a : produced) {
      if (a.version() > 1) {
        Artifact before =
            services.artifacts().versions(runId, stageId, a.type()).get(a.version() - 2);
        if (!before.contentHash().equals(a.contentHash())) {
          changes.add(
              Map.of("type", a.type(), "before", refPayload(before), "after", refPayload(a)));
        }
      }
    }
    if (!changes.isEmpty()) {
      replan(
          "artifact-changed",
          List.of(stageId),
          false,
          stageId + " produced different output",
          Actor.SYSTEM,
          Map.of("changes", changes),
          false,
          Optional.empty());
    }
  }

  /**
   * Invalidates {@link Replanner#affected} stages: records REPLAN_TRIGGERED (what changed, with
   * before/after hashes, what is invalidated, which approvals are voided), optionally restores the
   * checkpoint of the earliest invalidated stage, then emits STAGE_INVALIDATED for each (in-flight
   * attempts become stale). The scheduler re-runs them as they become ready; voided approvals must
   * be given again.
   *
   * @param alwaysRecord record REPLAN_TRIGGERED even when nothing is invalidated (amendments)
   * @param feedback for the changed stages' next attempt
   */
  private void replan(
      String trigger,
      Collection<String> changed,
      boolean alwaysRecord,
      String reason,
      Actor by,
      Map<String, ?> details,
      boolean rollback,
      Optional<String> feedback) {
    boolean includeChanged = trigger.equals("amendment");
    List<String> affected = Replanner.affected(dag, current.statuses(), changed, includeChanged);
    if (affected.isEmpty() && !alwaysRecord) {
      return;
    }
    List<String> voided = affected.stream().filter(this::approvalStands).toList();
    Optional<CheckpointRef> checkpoint =
        rollback
            ? affected.stream().map(this::latestCheckpoint).flatMap(Optional::stream).findFirst()
            : Optional.empty();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("trigger", trigger);
    payload.put("reason", reason);
    payload.put("changed", List.copyOf(changed));
    payload.put("invalidated", affected);
    payload.put("voidedApprovals", voided);
    payload.putAll(details);
    checkpoint.ifPresent(c -> payload.put("restoredCheckpoint", c.id()));
    runEvent(REPLAN_TRIGGERED, by, payload);
    long replan = current.lastSeq();

    checkpoint.ifPresent(c -> services.checkpoints().restore(c));
    for (String key : affected) {
      inFlight.remove(key);
      Future<?> task = running.remove(key);
      if (task != null) {
        task.cancel(true);
      }
      approvalRequests.remove(key);
      retryTimers.remove(key);
      Map<String, Object> invalidation = new LinkedHashMap<>();
      invalidation.put("reason", reason);
      invalidation.put("trigger", trigger);
      invalidation.put("replan", replan);
      if (voided.contains(key)) {
        invalidation.put("approvalVoided", true);
      }
      if (changed.contains(key)) {
        feedback.ifPresent(f -> invalidation.put("feedback", f));
      }
      stageEvent(STAGE_INVALIDATED, key, Actor.SYSTEM, invalidation);
    }
  }

  /** Whether the stage's current result rests on a human approval (voided if it is invalidated). */
  private boolean approvalStands(String stageKey) {
    if (statusOf(stageKey) != StageStatus.SUCCEEDED) {
      return false;
    }
    List<Event> events = stageEvents(stageKey);
    for (int i = events.size() - 1; i >= 0; i--) {
      EventType type = events.get(i).type();
      if (type == APPROVAL_GRANTED) {
        return true;
      }
      if (type == STAGE_READY) {
        return false;
      }
    }
    return false;
  }

  private static Map<String, Object> refPayload(Artifact a) {
    return Map.of(
        "artifactId", a.id(),
        "type", a.type(),
        "version", a.version(),
        "contentHash", a.contentHash());
  }

  // ---- safe stop, budgets, rollback -----------------------------------------------------------

  /**
   * Begins a safe stop: nothing new is dispatched; running attempts may finish within the grace
   * period (no grace for {@code immediate}, the kill switch); then it checkpoints and halts.
   * Repeated requests are no-ops, except that a kill cuts a running grace period short.
   */
  private void requestSafeStop(String reason, Actor by, boolean immediate, Map<String, ?> details) {
    RunStatus status = current.run().status();
    if (status.isTerminal() || status == RunStatus.HALTED) {
      return;
    }
    if (stopping != null) {
      if (immediate) {
        finishSafeStop();
      }
      return;
    }
    Duration grace = immediate ? Duration.ZERO : services.safeStopGrace();
    Map<String, Object> payload = new LinkedHashMap<>(details);
    payload.put("reason", reason);
    payload.put("grace", grace);
    payload.put("running", List.copyOf(inFlight.keySet()));
    runEvent(SAFE_STOP_REQUESTED, by, payload);
    stopping = new SafeStop(reason, by, current.lastSeq(), new ArrayList<>());
    if (inFlight.isEmpty() || grace.isZero()) {
      finishSafeStop();
    } else {
      later(grace, new GraceExpired(stopping.request()));
    }
  }

  /** Cancels what still runs, checkpoints the workspace and halts the run. */
  private void finishSafeStop() {
    SafeStop stop = stopping;
    stopping = null;
    List<String> cancelled = List.copyOf(inFlight.keySet());
    inFlight.clear(); // anything that still reports back is stale
    running.values().forEach(task -> task.cancel(true));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", stop.reason());
    if (!cancelled.isEmpty()) {
      payload.put("cancelled", cancelled);
    }
    if (!stop.notes().isEmpty()) {
      payload.put("notes", List.copyOf(stop.notes()));
    }
    try {
      CheckpointRef ref = services.checkpoints().create(runId, "safe-stop");
      runEvent(
          CHECKPOINT_CREATED,
          Actor.SYSTEM,
          Map.of("checkpointId", ref.id(), "reason", "safe stop"));
      payload.put("checkpointId", ref.id());
    } catch (RuntimeException e) {
      payload.put("checkpointError", e.toString()); // halting matters more than the checkpoint
    }
    runEvent(RUN_HALTED, stop.by(), payload);
  }

  /** Safe-stops the run once a budget is exceeded (checked after every command). */
  private void checkBudgets() {
    RunStatus status = current.run().status();
    if (status.isTerminal() || status == RunStatus.HALTED || stopping != null) {
      return;
    }
    budget
        .check(elapsed())
        .ifPresent(exceeded -> {
          runEvent(
              BUDGET_EXCEEDED,
              Actor.SYSTEM,
              Map.of(
                  "budget", exceeded.budget(),
                  "limit", exceeded.limit(),
                  "used", exceeded.used()));
          requestSafeStop(
              exceeded.describe(), Actor.SYSTEM, false, Map.of("budget", exceeded.budget()));
        });
  }

  /** An attempt's LLM use goes into the log, so the budget survives a restart. */
  private void recordUsage(String stageKey, Usage usage) {
    if (usage.llmCalls() > 0 || usage.tokens() > 0) {
      stageEvent(
          USAGE_RECORDED,
          stageKey,
          actorOf(stages.get(stageKey)),
          Map.of("llmCalls", usage.llmCalls(), "tokens", usage.tokens()));
    }
  }

  /** Agents that keep proposing denied actions are stopped for a human to look at. */
  private void checkDenials(List<PolicyCheck> checks) {
    if (checks.stream().noneMatch(c -> c.decision().denied())) {
      return;
    }
    List<Event> log = services.events().events(runId);
    long denials = 0;
    for (int i = log.size() - 1; i >= 0; i--) {
      Event e = log.get(i);
      if (e.type() == RUN_RESUMED) {
        break;
      }
      if (e.type() == POLICY_EVALUATED
          && "DENY".equals(e.payloadString("effect").orElse(""))
          && e.payload().containsKey("riskScore")) { // agent actions, not approval refusals
        denials++;
      }
    }
    if (denials >= MAX_DENIALS) {
      requestSafeStop(
          denials + " policy denials since the last resume", Actor.SYSTEM, false, Map.of());
    }
  }

  /**
   * Restores the checkpoint taken before {@code stageId} and marks it and everything downstream
   * that had started ROLLED_BACK (running attempts are cancelled); the scheduler runs them again.
   */
  private void onRollback(Rollback r) {
    if (current.run().status().isTerminal() || !stages.containsKey(r.stageId())) {
      LOG.log(System.Logger.Level.WARNING, "ignored rollback to {0}", r.stageId());
      return;
    }
    Optional<CheckpointRef> checkpoint = latestCheckpoint(r.stageId());
    if (checkpoint.isEmpty()) {
      LOG.log(
          System.Logger.Level.WARNING, "ignored rollback: no checkpoint before {0}", r.stageId());
      return;
    }
    services.checkpoints().restore(checkpoint.get());
    List<String> affected = new ArrayList<>();
    affected.add(r.stageId());
    affected.addAll(dag.downstreamClosure(r.stageId()));
    for (String key : affected) {
      if (StageStateMachine.canTransition(statusOf(key), StageEvent.ROLL_BACK)) {
        inFlight.remove(key);
        Future<?> task = running.remove(key);
        if (task != null) {
          task.cancel(true);
        }
        approvalRequests.remove(key);
        retryTimers.remove(key);
        stageEvent(
            ROLLED_BACK,
            key,
            r.by(),
            Map.of(
                "reason", r.reason(),
                "checkpointId", checkpoint.get().id(),
                "rolledBackTo", r.stageId()));
      }
    }
  }

  /**
   * If the stage keeps a checkpoint and its last attempt wrote files, restores the checkpoint
   * (automatic rollback, so the next attempt or a human starts from a clean workspace).
   *
   * @return the restored checkpoint's id
   */
  private Optional<String> restoreIfWritten(String stageKey) {
    StageDefinition def = stages.get(stageKey);
    if (def.checkpoint() == CheckpointKind.NONE || !attemptWroteFiles(stageKey)) {
      return Optional.empty();
    }
    Optional<CheckpointRef> checkpoint = latestCheckpoint(stageKey);
    checkpoint.ifPresent(ref -> services.checkpoints().restore(ref));
    return checkpoint.map(CheckpointRef::id);
  }

  /** Whether the stage's latest attempt was allowed (or asked approval) to write files. */
  private boolean attemptWroteFiles(String stageKey) {
    List<Event> events = stageEvents(stageKey);
    for (int i = events.size() - 1; i >= 0; i--) {
      Event e = events.get(i);
      if (e.type() == STAGE_STARTED) {
        return false;
      }
      String action = e.payloadString("action").orElse("");
      if (e.type() == POLICY_EVALUATED
          && (action.startsWith("file_write ") || action.startsWith("diff "))
          && !"DENY".equals(e.payloadString("effect").orElse(""))) {
        return true;
      }
    }
    return false;
  }

  private Optional<CheckpointRef> latestCheckpoint(String stageId) {
    return stageEvents(stageId).stream()
        .filter(e -> e.type() == CHECKPOINT_CREATED)
        .reduce((first, second) -> second)
        .flatMap(e -> e.payloadString("checkpointId"))
        .map(id -> new CheckpointRef(id, runId, stageId));
  }

  private static Map<String, Object> withRestored(
      Map<String, Object> payload, Optional<String> restored) {
    if (restored.isEmpty()) {
      return payload;
    }
    Map<String, Object> copy = new LinkedHashMap<>(payload);
    copy.put("restoredCheckpoint", restored.get());
    return copy;
  }

  // ---- failure handling -----------------------------------------------------------------------

  /**
   * An attempt failed. In order: a validation failure of a stage with {@code onFailure} loops back
   * (while loops remain); a retryable failure is retried after a backoff (while attempts remain),
   * then the stage's fallback is tried once; otherwise the stage fails, which fails the run (or,
   * for a {@code highImpact} stage, halts it for a human).
   */
  private void failAttempt(String stageKey, String reason, FailureKind kind, Actor actor) {
    StageDefinition def = stages.get(stageKey);
    StageInstance stage = current.stages().get(stageKey);
    Optional<String> restored = restoreIfWritten(stageKey); // the next attempt starts clean
    if (stopping != null) {
      // nothing new starts during a safe stop: the stage is halted with the run, re-runs on resume
      stopping.notes().add(stageKey + ": " + reason);
      return;
    }
    if (stage.status() == StageStatus.RUNNING) {
      if (kind == FailureKind.VALIDATION && def.onFailure().isPresent() && loopBack(def, reason)) {
        return;
      }
      if (kind.retryable() && stage.attempt() < def.retry().maxAttempts()) {
        scheduleRetry(def, stage, reason, kind, restored);
        return;
      }
      if (kind.retryable() && def.fallback().isPresent() && !fallbackUsedThisLoop(stageKey)) {
        String strategy = def.fallback().get().strategy();
        stageEvent(
            FALLBACK_USED,
            stageKey,
            Actor.SYSTEM,
            withRestored(
                Map.of("strategy", strategy, "reason", reason, "kind", kind),
                restored)); // → RETRYING
        dispatch(stageKey, def, Optional.of(strategy));
        return;
      }
    }
    if (def.highImpact()) {
      requestSafeStop(
          "high-impact stage " + stageKey + " failed: " + reason,
          Actor.SYSTEM,
          false,
          Map.of("stageId", stageKey, "kind", kind));
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("kind", kind);
    restored.ifPresent(id -> payload.put("restoredCheckpoint", id));
    stageEvent(STAGE_FAILED, stageKey, actor, payload);
  }

  private void scheduleRetry(
      StageDefinition def,
      StageInstance stage,
      String reason,
      FailureKind kind,
      Optional<String> restored) {
    Duration delay = def.retry().delayAfter(stage.attempt(), ThreadLocalRandom.current());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", reason);
    payload.put("kind", kind);
    payload.put("attempt", stage.attempt());
    payload.put("maxAttempts", def.retry().maxAttempts());
    payload.put("delay", delay);
    restored.ifPresent(id -> payload.put("restoredCheckpoint", id));
    stageEvent(STAGE_RETRIED, def.id(), Actor.SYSTEM, payload); // → RETRYING
    long retry = current.lastSeq();
    retryTimers.put(def.id(), retry);
    later(delay, new RetryDue(def.id(), retry));
  }

  /** Starts the retry if the stage is still waiting for the backoff the timer belongs to. */
  private void onRetryDue(RetryDue r) {
    if (stopping == null
        && statusOf(r.stageKey()) == StageStatus.RETRYING
        && Long.valueOf(r.retry()).equals(retryTimers.get(r.stageKey()))) {
      retryTimers.remove(r.stageKey());
      dispatch(r.stageKey(), stages.get(r.stageKey()));
    }
  }

  /**
   * Re-runs {@code onFailure.loopTo} with this failure as feedback: the failing stage, the target
   * and everything downstream of the target are invalidated, and the scheduler runs them again.
   *
   * @return false when the loops are used up
   */
  private boolean loopBack(StageDefinition def, String reason) {
    OnFailure onFailure = def.onFailure().orElseThrow();
    long loopsUsed =
        stageEvents(def.id()).stream()
            .filter(e -> e.type() == STAGE_INVALIDATED && e.payload().containsKey("loopTo"))
            .count();
    if (loopsUsed >= onFailure.maxLoops()) {
      return false;
    }
    long loop = loopsUsed + 1;
    String target = onFailure.loopTo();
    String feedback =
        def.id()
            + " failed: "
            + reason
            + onFailure
                .feedback()
                .flatMap(type -> services.artifacts().latestOfType(runId, type))
                .map(a -> " (see " + a.id() + ")")
                .orElse("");
    stageEvent(
        STAGE_INVALIDATED,
        def.id(),
        Actor.SYSTEM,
        Map.of(
            "reason",
            "looping back to " + target,
            "loopTo",
            target,
            "loop",
            loop,
            "maxLoops",
            onFailure.maxLoops()));
    stageEvent(
        STAGE_INVALIDATED,
        target,
        Actor.SYSTEM,
        Map.of("reason", "re-run for " + def.id(), "loopFrom", def.id(), "feedback", feedback));
    invalidateDownstream(target, "upstream " + target + " is re-running (loop " + loop + ")");
    return true;
  }

  /** Whether the fallback already ran since the stage last became ready. */
  private boolean fallbackUsedThisLoop(String stageKey) {
    List<Event> events = stageEvents(stageKey);
    for (int i = events.size() - 1; i >= 0; i--) {
      EventType type = events.get(i).type();
      if (type == FALLBACK_USED) {
        return true;
      }
      if (type == STAGE_READY) {
        return false;
      }
    }
    return false;
  }

  private List<Event> stageEvents(String stageKey) {
    return services.events().events(runId).stream()
        .filter(e -> e.stageId().filter(stageKey::equals).isPresent())
        .toList();
  }

  /** Delivers {@code command} to the loop after {@code delay}, unless the coordinator closed. */
  private void later(Duration delay, Command command) {
    Runnable deliver =
        () -> {
          if (!closed) {
            inbox.add(command);
          }
        };
    if (delay.isZero()) {
      deliver.run();
    } else {
      CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(deliver);
    }
  }

  /** Each policy check becomes a POLICY_EVALUATED event (the audit trail of what agents asked). */
  private void recordChecks(String stageKey, List<PolicyCheck> checks) {
    for (PolicyCheck check : checks) {
      stageEvent(
          POLICY_EVALUATED,
          stageKey,
          actorOf(stages.get(stageKey)),
          Map.of(
              "action", check.describe(),
              "effect", check.decision().effect(),
              "riskScore", check.decision().riskScore(),
              "matchedPolicies",
                  check.decision().matchedPolicies().stream()
                      .map(m ->
                          Map.of(
                              "policyId", m.policyId(),
                              "effect", m.effect(),
                              "reason", m.reason()))
                      .toList()));
    }
  }

  private static String policyIds(PolicyCheck check, Effect effect) {
    return String.join(
        ", ",
        check.decision().matchedPolicies().stream()
            .filter(m -> m.effect() == effect)
            .map(PolicyMatch::policyId)
            .toList());
  }

  /** The highest of the agent's own assessment and its policy checks; empty when neither exists. */
  private static OptionalInt stageRisk(StageCompleted c) {
    IntStream scores =
        IntStream.concat(
            c.reportedRisk().stream(), c.checks().stream().mapToInt(x -> x.decision().riskScore()));
    return scores.max();
  }

  /** Stages downstream of {@code stageKey} that have started or finished are invalidated. */
  private void invalidateDownstream(String stageKey, String reason) {
    for (String downstream : dag.downstreamClosure(stageKey)) {
      if (StageStateMachine.canTransition(statusOf(downstream), StageEvent.INVALIDATE)) {
        inFlight.remove(downstream);
        approvalRequests.remove(downstream);
        stageEvent(STAGE_INVALIDATED, downstream, Actor.SYSTEM, Map.of("reason", reason));
      }
    }
  }

  /** Statuses for the ready-check: invalidated and rolled-back stages are pending again. */
  private Map<String, StageStatus> schedulable() {
    Map<String, StageStatus> statuses = new LinkedHashMap<>(current.statuses());
    statuses.replaceAll((k, s) ->
        s == StageStatus.INVALIDATED || s == StageStatus.ROLLED_BACK ? StageStatus.PENDING : s);
    return statuses;
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
      return new StageFailed(
          stageId, dispatch, reason, FailureKind.of(cause), context.checks(), context.usage());
    }
    return switch (result) {
      case null ->
          new StageFailed(
              stageId,
              dispatch,
              "agent returned no result",
              FailureKind.FATAL,
              context.checks(),
              context.usage());
      case AgentResult.Success s ->
          new StageCompleted(
              stageId,
              dispatch,
              s,
              context.reads(),
              context.checks(),
              context.reportedRisk(),
              context.usage());
      case AgentResult.Failure f ->
          new StageFailed(
              stageId, dispatch, f.reason(), f.kind(), context.checks(), context.usage());
    };
  }

  /** Why earlier attempts failed, loop-back reasons and rejection comments, oldest first. */
  private List<String> feedback(String stageKey) {
    List<String> feedback = new ArrayList<>();
    for (Event e : services.events().events(runId)) {
      if (e.stageId().filter(stageKey::equals).isEmpty()) {
        continue;
      }
      if (e.type() == STAGE_FAILED || e.type() == STAGE_RETRIED || e.type() == FALLBACK_USED) {
        e.payloadString("reason").ifPresent(feedback::add);
      } else if (e.type() == APPROVAL_REJECTED) {
        e.payloadString("comment").ifPresent(c -> feedback.add("rejected: " + c));
      } else if (e.type() == STAGE_INVALIDATED) {
        e.payloadString("feedback").ifPresent(feedback::add); // a loop-back's reason
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
