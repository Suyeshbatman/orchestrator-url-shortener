package com.acme.orchestrator.cli;

import com.acme.orchestrator.agents.llm.AnthropicLlmGateway;
import com.acme.orchestrator.agents.llm.JsonSchemas;
import com.acme.orchestrator.agents.llm.LlmClient;
import com.acme.orchestrator.agents.llm.LlmGateway;
import com.acme.orchestrator.agents.llm.LlmGateways;
import com.acme.orchestrator.agents.llm.PromptTemplates;
import com.acme.orchestrator.agents.llm.RecordingLlmGateway;
import com.acme.orchestrator.agents.llm.RedactingLlmGateway;
import com.acme.orchestrator.agents.llm.ReplayLlmGateway;
import com.acme.orchestrator.agents.llm.SecretRedactor;
import com.acme.orchestrator.agents.sdlc.AgentKit;
import com.acme.orchestrator.agents.sdlc.SdlcAgents;
import com.acme.orchestrator.agents.sdlc.ValidationAgent;
import com.acme.orchestrator.agents.tools.GitCheckpointManager;
import com.acme.orchestrator.agents.tools.MergeToMainAction;
import com.acme.orchestrator.agents.tools.Workspace;
import com.acme.orchestrator.agents.tools.WorkspaceManager;
import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.InMemoryArtifactStore;
import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentRegistry;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditReport;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.InMemoryEventStore;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.model.ArtifactTypes;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import com.acme.orchestrator.core.policy.PolicyEngine;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

/**
 * Runs a scenario end to end in this process: the standard workflow, the real agents, real Git
 * workspaces, real Maven validation, in-memory stores, and {@link ScriptedHuman} at the
 * checkpoints. Everything happens under {@code .orchestrator/scenarios/<id>/} (git-ignored): a bare
 * copy of the repository at the scenario's base (what the run clones and publishes to, so the real
 * repository is never touched), the workspace, and the run's event log.
 *
 * <p>LLM modes: {@code replay} (fixtures only), {@code record} (the live model, saving fixtures;
 * hand-written replies in {@code replies/} take precedence), {@code handwritten} (only hand-written
 * replies, saving fixtures: writing fixtures without an API key), {@code live}.
 */
public final class ScenarioRunner {

  public enum LlmMode {
    REPLAY,
    RECORD,
    HANDWRITTEN,
    LIVE
  }

  /** How it went. */
  public record Outcome(
      String runId, RunStatus status, boolean expected, AuditReport audit, Path workDir) {}

  static final String WORKFLOW = "sdlc-standard.yml";

  private final Environment env;
  private final PrintStream out;

  public ScenarioRunner(Environment env, PrintStream out) {
    this.env = env;
    this.out = out;
  }

  public Outcome run(String scenarioId, LlmMode mode, Duration timeout) throws Exception {
    Path root = env.repoRoot();
    Scenario scenario = Scenario.load(root.resolve("scenarios"), scenarioId);
    Path work = root.resolve(".orchestrator").resolve("scenarios").resolve(scenarioId);
    deleteTree(work);
    Files.createDirectories(work);

    // what the run clones and publishes to: a bare copy at the scenario's base
    Path remote = work.resolve("remote.git");
    String baseCommit = bareCopy(root, remote, scenario.base());
    WorkspaceManager workspaces =
        new WorkspaceManager(remote.toUri().toString(), work.resolve("worktrees"), "main");
    String runId = "scn-" + scenarioId;
    Workspace workspace = workspaces.create(runId);

    Clock clock = Clock.systemUTC();
    InMemoryEventStore events = new InMemoryEventStore(clock);
    InMemoryArtifactStore artifacts = new InMemoryArtifactStore(clock);
    PolicyEngine policies = PolicyEngine.load(root.resolve("config"));
    JsonSchemas schemas = new JsonSchemas();
    GateRegistry gates = GateRegistry.standard(schemas);
    WorkflowDefinition workflow =
        new WorkflowLoader(gates).load(root.resolve("config/workflows").resolve(WORKFLOW));

    LlmGateway llm = gateway(scenario, mode);
    AgentKit kit =
        new AgentKit(new LlmClient(llm, schemas), new PromptTemplates(), AgentKit.SHORTENER);
    Map<String, Agent> agents =
        new HashMap<>(SdlcAgents.standard(kit, policies, ValidationAgent.Settings.SHORTENER));
    agents.put(MergeToMainAction.NAME, new MergeToMainAction(workspaces::create, "main", clock));

    Run run = Run.create(runId, workflow, scenario.mode(), scenario.autonomy());
    artifacts.save(
        new ArtifactDraft(
            runId,
            ArtifactTypes.INPUT_STAGE,
            ArtifactTypes.REQUIREMENT,
            CanonicalJson.write(Map.of("text", scenario.requirement())),
            ProducedBy.deterministic("human:bob"),
            List.of(),
            "scenario " + scenarioId));

    out.printf(
        ">> %s  (%s, %s, llm %s, base %s)%n",
        scenarioId,
        scenario.mode(),
        scenario.autonomy(),
        mode.name().toLowerCase(),
        baseCommit.substring(0, 10));
    Coordinator coordinator =
        Coordinator.start(
            workflow,
            run,
            new Coordinator.Services(
                    events,
                    artifacts,
                    AgentRegistry.of(agents),
                    gates,
                    ToolRegistry.builder().register(Workspace.class, workspace).build(),
                    policies)
                .withCheckpoints(new GitCheckpointManager(workspaces::create, clock)));
    EventPrinter printer = new EventPrinter(events, runId, out);
    printer.start();
    ScriptedHuman human =
        new ScriptedHuman(
            scenario,
            coordinator::submit,
            Actor.human("alice"),
            Actor.human("bob"),
            s -> out.println("  [human] " + s));
    long deadline = System.nanoTime() + timeout.toNanos();
    RunState state;
    try {
      while (true) {
        state = coordinator.awaitSettled(left(deadline));
        RunStatus status = state.run().status();
        if (status.isTerminal() || status == RunStatus.HALTED) {
          break;
        }
        long seen = state.lastSeq();
        human.act(state, events.events(runId));
        coordinator.await(s -> s.lastSeq() > seen, left(deadline));
      }
    } catch (TimeoutException e) {
      state = coordinator.state();
      out.println("FAILED: timed out after " + timeout + " in status " + state.run().status());
    } finally {
      coordinator.close();
      printer.stopAndFlush();
    }

    AuditReport audit = new AuditVerifier(events).verify(runId);
    writeLog(work.resolve("events.jsonl"), events.events(runId));
    if (mode == LlmMode.RECORD || mode == LlmMode.HANDWRITTEN) {
      writeManifest(scenario, baseCommit, mode);
    }
    boolean expected = state.run().status().name().equals(scenario.expect());
    out.printf(
        "%s %s ended %s (expected %s), audit %s, %d events, log %s%n",
        expected ? "OK" : "FAILED",
        runId,
        state.run().status(),
        scenario.expect(),
        audit.valid() ? "valid" : "BROKEN at #" + audit.firstBrokenSeq().orElse(-1L),
        audit.eventCount(),
        root.relativize(work.resolve("events.jsonl")));
    return new Outcome(runId, state.run().status(), expected, audit, work);
  }

  private LlmGateway gateway(Scenario scenario, LlmMode mode) throws IOException {
    LlmGateway gateway =
        switch (mode) {
          case REPLAY -> new ReplayLlmGateway(scenario.replayDir());
          case RECORD -> {
            clearFixtures(scenario.replayDir());
            yield new RecordingLlmGateway(
                new ScriptedLlmGateway(scenario.repliesDir(), Optional.of(live())),
                scenario.replayDir());
          }
          case HANDWRITTEN -> {
            clearFixtures(scenario.replayDir());
            yield new RecordingLlmGateway(
                new ScriptedLlmGateway(scenario.repliesDir(), Optional.empty()),
                scenario.replayDir());
          }
          case LIVE -> live();
        };
    return new RedactingLlmGateway(gateway, SecretRedactor.fromEnvironment(env.variables()));
  }

  private LlmGateway live() {
    if (!env.has("ANTHROPIC_API_KEY")) {
      throw new IllegalStateException(
          "record/live mode needs ANTHROPIC_API_KEY (in the environment or the repo-root .env)");
    }
    Map<String, String> vars = new HashMap<>(env.variables());
    vars.put("ORCH_LLM_MODE", "live");
    LlmGateways.Config config = LlmGateways.Config.fromEnvironment(vars, Path.of("."));
    out.println(
        "  model "
            + config.model()
            + config.fallbackModel().map(f -> " (fallback " + f + ")").orElse(""));
    return new AnthropicLlmGateway(
        config.apiKey().orElseThrow(),
        Optional.empty(),
        AnthropicLlmGateway.Settings.of(config.model(), config.fallbackModel()));
  }

  /** A bare clone of the repository whose main is {@code base}; returns that commit. */
  static String bareCopy(Path repo, Path remote, String base) throws Exception {
    ObjectId commit;
    try (Git source = Git.open(repo.toFile())) {
      commit = source.getRepository().resolve(base + "^{commit}");
      if (commit == null) {
        throw new IllegalArgumentException("no commit " + base + " in " + repo);
      }
    }
    try (Git bare =
        Git.cloneRepository()
            .setURI(repo.toUri().toString())
            .setDirectory(remote.toFile())
            .setBare(true)
            .call()) {
      Repository r = bare.getRepository();
      RefUpdate main = r.updateRef("refs/heads/main");
      main.setNewObjectId(commit);
      main.setForceUpdate(true);
      main.update();
      r.updateRef("HEAD").link("refs/heads/main");
    }
    return commit.name();
  }

  private void writeManifest(Scenario scenario, String baseCommit, LlmMode mode)
      throws IOException {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("scenario", scenario.id());
    manifest.put("baseCommit", baseCommit);
    manifest.put("recordedWith", mode.name().toLowerCase());
    manifest.put(
        "model", env.variables().getOrDefault("ORCH_LLM_MODEL", LlmGateways.Config.DEFAULT_MODEL));
    manifest.put(
        "note",
        "Fixtures are keyed by the exact prompt. They stay valid while the prompts, schemas and the"
            + " shortener-service code at the base commit stay the same.");
    Files.createDirectories(scenario.replayDir());
    Files.writeString(
        scenario.replayDir().resolve("manifest.json"),
        CanonicalJson.write(manifest) + "\n",
        StandardCharsets.UTF_8);
  }

  private static void clearFixtures(Path replayDir) throws IOException {
    if (!Files.isDirectory(replayDir)) {
      return;
    }
    try (Stream<Path> files = Files.list(replayDir)) {
      for (Path f :
          files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.json")).toList()) {
        Files.delete(f);
      }
    }
  }

  private static void writeLog(Path file, List<Event> log) throws IOException {
    StringBuilder lines = new StringBuilder();
    for (Event e : log) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("seq", e.seq());
      line.put("type", e.type().name());
      e.stageId().ifPresent(s -> line.put("stage", s));
      line.put("actor", e.actor().toString());
      line.put("ts", e.timestamp().toString());
      line.put("payload", e.payload());
      line.put("hash", e.hash());
      lines.append(CanonicalJson.write(line)).append('\n');
    }
    Files.writeString(file, lines.toString(), StandardCharsets.UTF_8);
  }

  static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        p.toFile().setWritable(true); // git pack files are read-only on Windows
        Files.delete(p);
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private static Duration left(long deadline) {
    long nanos = deadline - System.nanoTime();
    return Duration.ofNanos(Math.max(nanos, 1));
  }
}
