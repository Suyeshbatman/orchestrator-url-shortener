package com.acme.orchestrator.app.run;

import com.acme.orchestrator.app.api.ApiException;
import com.acme.orchestrator.app.api.ApiKeys.Principal;
import com.acme.orchestrator.app.api.ApiModels.AuditView;
import com.acme.orchestrator.app.api.ApiModels.CreateRunRequest;
import com.acme.orchestrator.app.api.ApiModels.Edge;
import com.acme.orchestrator.app.api.ApiModels.RunSummary;
import com.acme.orchestrator.app.api.ApiModels.RunView;
import com.acme.orchestrator.app.api.ApiModels.StageNode;
import com.acme.orchestrator.app.api.ProblemTypes;
import com.acme.orchestrator.app.persistence.ApprovalRepository;
import com.acme.orchestrator.app.persistence.ApprovalRepository.ApprovalRow;
import com.acme.orchestrator.app.persistence.RunRepository;
import com.acme.orchestrator.app.persistence.RunRepository.RunRow;
import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.dag.Dag;
import com.acme.orchestrator.core.engine.Command;
import com.acme.orchestrator.core.engine.Command.ApprovalDecision;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.engine.TaskExpansion;
import com.acme.orchestrator.core.event.AuditReport;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.StageDefinition;
import com.acme.orchestrator.core.model.StageInstance;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;

/** What the API does with runs: start them, show them, and pass human commands to them. */
public final class RunService {

  public static final String STANDARD_WORKFLOW = "sdlc-standard.yml";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final OrchestratorProperties props;
  private final RunRepository runs;
  private final ApprovalRepository approvals;
  private final EventStore events;
  private final ArtifactStore artifacts;
  private final RunLauncher launcher;
  private final ActiveRuns active;
  private final GateRegistry gates;
  private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();

  public RunService(
      OrchestratorProperties props,
      RunRepository runs,
      ApprovalRepository approvals,
      EventStore events,
      ArtifactStore artifacts,
      RunLauncher launcher,
      ActiveRuns active,
      GateRegistry gates) {
    this.props = props;
    this.runs = runs;
    this.approvals = approvals;
    this.events = events;
    this.artifacts = artifacts;
    this.launcher = launcher;
    this.active = active;
    this.gates = gates;
  }

  // ---- starting -------------------------------------------------------------------------------

  /** Registers the run, stores the requirement as its first artifact, and launches it. */
  public RunView create(CreateRunRequest request, Principal by) {
    boolean scenario = request.scenarioId() != null && !request.scenarioId().isBlank();
    boolean text = request.requirementText() != null && !request.requirementText().isBlank();
    if (scenario == text) {
      throw ApiException.badRequest("give exactly one of scenarioId or requirementText");
    }
    String requirement =
        scenario ? scenarioRequirement(request.scenarioId()) : request.requirementText().strip();
    String workflowFile =
        request.workflow() == null || request.workflow().isBlank()
            ? STANDARD_WORKFLOW
            : request.workflow() + ".yml";
    if (!Files.isRegularFile(props.workflowsDir().resolve(workflowFile))) {
      throw ApiException.badRequest("no workflow " + workflowFile);
    }
    WorkflowDefinition workflow = workflow(workflowFile);
    String id = "run-" + HexFormat.of().formatHex(RANDOM.generateSeed(6));
    Run run =
        Run.create(
            id,
            workflow,
            request.mode(),
            request.autonomyLevel() == null
                ? AutonomyLevel.L1_SUPERVISED
                : request.autonomyLevel());
    Optional<String> scenarioId = scenario ? Optional.of(request.scenarioId()) : Optional.empty();
    runs.registerLaunch(run, workflowFile, scenarioId);
    artifacts.save(
        new ArtifactDraft(
            id,
            ArtifactTypes.INPUT_STAGE,
            ArtifactTypes.REQUIREMENT,
            CanonicalJson.write(Map.of("text", requirement)),
            ProducedBy.deterministic(by.actor().toString()),
            List.of(),
            scenario ? "scenario " + request.scenarioId() : "submitted by " + by.name()));
    try {
      launcher.launch(runs.find(id).orElseThrow(), run);
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          ProblemTypes.RUN_START_FAILED,
          id + ": " + e.getMessage());
    }
    return view(id);
  }

  private String scenarioRequirement(String scenarioId) {
    Path file = props.scenariosPath().resolve(scenarioId).resolve("requirement.md");
    if (!Files.isRegularFile(file)) {
      throw ApiException.notFound(ProblemTypes.SCENARIO_NOT_FOUND, "no scenario " + scenarioId);
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- reading --------------------------------------------------------------------------------

  public List<RunSummary> list() {
    return runs.all().stream().map(this::summary).toList();
  }

  public RunView view(String runId) {
    RunRow row = row(runId);
    WorkflowDefinition workflow = workflow(row.workflowFile());
    Map<String, StageInstance> stages = state(runId).map(RunState::stages).orElse(Map.of());
    // a fanned-out stage shows as its tasks (implementation.T1, ...), as the coordinator runs it
    Map<String, TaskExpansion> expansions = TaskExpansion.latest(events.events(runId));
    Dag<String> graph = TaskExpansion.graph(workflow, expansions);
    List<String> keys = new ArrayList<>();
    for (StageDefinition def : workflow.stages()) { // in workflow order
      if (graph.contains(def.id())) {
        keys.add(def.id());
      } else {
        keys.addAll(expansions.get(def.id()).keys());
      }
    }
    List<StageNode> nodes = new ArrayList<>();
    List<Edge> edges = new ArrayList<>();
    for (String key : keys) {
      StageDefinition def = workflow.stage(TaskExpansion.stageIdOf(key)).orElseThrow();
      StageInstance s = stages.get(key);
      nodes.add(
          new StageNode(
              key,
              def.executor().name(),
              s == null ? "PENDING" : s.status().name(),
              s == null ? 0 : s.attempt(),
              s == null ? 0 : s.loopCount(),
              s == null ? null : s.startedAt().orElse(null),
              s == null ? null : s.endedAt().orElse(null),
              def.approval().required()));
      graph.dependenciesOf(key).forEach(dep -> edges.add(new Edge(dep, key)));
    }
    return new RunView(summary(row), nodes, edges);
  }

  /** The DAG as Mermaid, each stage coloured by its status. */
  public String mermaid(String runId) {
    RunView view = view(runId);
    StringBuilder m = new StringBuilder("flowchart LR\n");
    for (StageNode n : view.nodes()) {
      m.append("  ")
          .append(mermaidId(n.id()))
          .append("[\"")
          .append(n.id())
          .append("<br/>")
          .append(n.status())
          .append(n.attempt() > 1 ? " (" + n.attempt() + " attempts)" : "")
          .append("\"]:::")
          .append(n.status().toLowerCase(java.util.Locale.ROOT))
          .append('\n');
    }
    view.edges()
        .forEach(e ->
            m.append("  ")
                .append(mermaidId(e.from()))
                .append(" --> ")
                .append(mermaidId(e.to()))
                .append('\n'));
    for (Map.Entry<String, String> style : MERMAID_STYLES.entrySet()) {
      m.append("  classDef ")
          .append(style.getKey())
          .append(' ')
          .append(style.getValue())
          .append('\n');
    }
    return m.toString();
  }

  /** A Mermaid node id: task keys contain a dot, which Mermaid doesn't allow in ids. */
  private static String mermaidId(String key) {
    return key.replaceAll("[^A-Za-z0-9_]", "_");
  }

  private static final Map<String, String> MERMAID_STYLES =
      new java.util.LinkedHashMap<>(
          Map.ofEntries(
              Map.entry("pending", "fill:#eeeeee,stroke:#9e9e9e"),
              Map.entry("ready", "fill:#e3f2fd,stroke:#1e88e5"),
              Map.entry("running", "fill:#fff59d,stroke:#f9a825"),
              Map.entry("retrying", "fill:#ffe082,stroke:#ff8f00"),
              Map.entry("waiting_approval", "fill:#ffcc80,stroke:#ef6c00"),
              Map.entry("succeeded", "fill:#c8e6c9,stroke:#2e7d32"),
              Map.entry("skipped", "fill:#f5f5f5,stroke:#bdbdbd,stroke-dasharray:4"),
              Map.entry("failed", "fill:#ef9a9a,stroke:#c62828"),
              Map.entry("halted", "fill:#b0bec5,stroke:#455a64"),
              Map.entry("invalidated", "fill:#e1bee7,stroke:#8e24aa"),
              Map.entry("rolled_back", "fill:#90caf9,stroke:#1565c0")));

  public List<Event> events(String runId, long afterSeq) {
    row(runId);
    return events.events(runId).stream().filter(e -> e.seq() > afterSeq).toList();
  }

  public AuditView audit(String runId) {
    row(runId);
    AuditReport report = new AuditVerifier(events).verify(runId);
    return new AuditView(
        runId,
        report.valid(),
        report.eventCount(),
        report.firstBrokenSeq().orElse(null),
        report.problem().map(Enum::name).orElse(null),
        report.headHash());
  }

  // ---- commands -------------------------------------------------------------------------------

  public void halt(String runId, String reason, Principal by) {
    coordinator(runId)
        .submit(
            new Command.Halt(
                reason == null || reason.isBlank() ? "halted by " + by.name() : reason,
                by.actor()));
  }

  public void resume(String runId, Principal by) {
    coordinator(runId).submit(new Command.Resume(by.actor()));
  }

  public void rollback(String runId, String toStage, String reason, Principal by) {
    Coordinator c = coordinator(runId);
    if (workflow(row(runId).workflowFile()).stage(toStage).isEmpty()) {
      throw ApiException.badRequest("the workflow has no stage " + toStage);
    }
    c.submit(
        new Command.Rollback(
            toStage,
            reason == null || reason.isBlank() ? "rolled back by " + by.name() : reason,
            by.actor()));
  }

  public void amend(String runId, String text, Principal by) {
    coordinator(runId).submit(new Command.AmendRequirement(text.strip(), by.actor()));
  }

  public void answer(String runId, String answersJson, Principal by) {
    coordinator(runId).submit(new Command.ProvideAnswers(answersJson, by.actor()));
  }

  /** Passes a human decision to the run; the coordinator re-checks that it is still pending. */
  public ApprovalRow decide(
      String approvalId, ApprovalDecision.Decision decision, String comment, Principal by) {
    ApprovalRow approval =
        approvals
            .find(approvalId)
            .orElseThrow(() ->
                ApiException.notFound(
                    ProblemTypes.APPROVAL_NOT_FOUND, "no approval " + approvalId));
    if (!approval.status().equals("PENDING")) {
      throw ApiException.conflict(
          ProblemTypes.APPROVAL_NOT_PENDING, approvalId + " is already " + approval.status());
    }
    coordinator(approval.runId())
        .submit(
            new ApprovalDecision(
                approval.stageId(), by.actor(), ApprovalDecision.APPROVER, decision, comment));
    return approval;
  }

  // ---- helpers --------------------------------------------------------------------------------

  private RunRow row(String runId) {
    return runs.find(runId)
        .orElseThrow(() -> ApiException.notFound(ProblemTypes.RUN_NOT_FOUND, "no run " + runId));
  }

  private Coordinator coordinator(String runId) {
    RunRow row = row(runId);
    if (Set.of("COMPLETED", "FAILED", "ROLLED_BACK").contains(row.status())) {
      throw ApiException.conflict(
          ProblemTypes.RUN_NOT_ACTIVE, runId + " is " + row.status() + " and takes no commands");
    }
    return active
        .get(runId)
        .orElseThrow(() ->
            ApiException.conflict(
                ProblemTypes.RUN_NOT_ACTIVE,
                runId + " is " + row.status() + " and takes no commands"));
  }

  private Optional<RunState> state(String runId) {
    List<Event> log = events.events(runId);
    return log.isEmpty() ? Optional.empty() : Optional.of(RunStateProjector.project(log));
  }

  private WorkflowDefinition workflow(String file) {
    return workflows.computeIfAbsent(
        file, f -> new WorkflowLoader(gates).load(props.workflowsDir().resolve(f)));
  }

  private RunSummary summary(RunRow row) {
    return new RunSummary(
        row.id(),
        row.workflowId(),
        row.scenarioId().orElse(null),
        row.mode(),
        row.autonomyLevel(),
        row.status(),
        active.get(row.id()).isPresent(),
        row.createdAt(),
        row.updatedAt());
  }
}
