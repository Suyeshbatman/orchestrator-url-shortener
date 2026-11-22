package com.acme.orchestrator.cli;

import com.acme.orchestrator.core.model.Hashing;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The end-to-end demo, in replay mode with no API key: starts a Postgres of its own, starts the
 * orchestrator app against a fresh database, runs the three scenarios through its HTTP API with the
 * scenarios' scripted humans, and prints each run's audit check and the metrics summary.
 *
 * <p>Everything it creates lives under {@code .orchestrator/demo/} (git-ignored): a bare copy of
 * this repository at the scenarios' base (what the runs clone and publish to), the workspaces, the
 * app's log. The API keys are made up for this one demo and never written anywhere.
 */
final class Demo {

  static final List<String> SCENARIOS =
      List.of("greenfield-qr-code", "brownfield-expiry-bf1", "ambiguous-abuse");
  static final String DATABASE = "orchestrator_demo";

  /**
   * The demo's own Postgres: a separate compose project on its own port with a demo-only password,
   * so it needs no .env and never touches the developer's containers, ports or data. Remove it with
   * {@code docker compose -p orchestrator-demo down -v}.
   */
  static final String COMPOSE_PROJECT = "orchestrator-demo";

  static final String POSTGRES_PORT = "5435";
  static final Map<String, String> POSTGRES =
      Map.of(
          "POSTGRES_USER", "app",
          "POSTGRES_PASSWORD", "orchestrator-demo-only",
          "POSTGRES_DB", "shortener",
          "POSTGRES_PORT", POSTGRES_PORT);
  static final Set<String> SHOWN =
      Set.of(
          "STAGE_SUCCEEDED",
          "STAGE_SKIPPED",
          "STAGE_FAILED",
          "STAGE_RETRIED",
          "FALLBACK_USED",
          "REPLAN_TRIGGERED",
          "APPROVAL_REJECTED",
          "RUN_COMPLETED",
          "RUN_FAILED");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Environment env;
  private final PrintStream out;
  private final URI url;

  Demo(Environment env, PrintStream out, URI url) {
    this.env = env;
    this.out = out;
    this.url = url;
  }

  int run(Duration perScenario) throws Exception {
    Path root = env.repoRoot();
    Path work = root.resolve(".orchestrator").resolve("demo");
    String operatorKey = randomKey();
    String approverKey = randomKey();
    OrchestratorApi operator = new OrchestratorApi(url, operatorKey);
    OrchestratorApi approver = operator.as(approverKey);
    if (operator.healthy()) {
      throw new IllegalStateException(
          "something already answers on " + url + ": stop the orchestrator app first");
    }

    banner(
        "1/5 a Postgres of its own (compose project "
            + COMPOSE_PROJECT
            + ", port "
            + POSTGRES_PORT
            + "), fresh database "
            + DATABASE);
    compose(root, "up", "-d", "--wait", "postgres");
    compose(
        root,
        "exec",
        "-T",
        "postgres",
        "sh",
        "-c",
        "dropdb -U \"$POSTGRES_USER\" --if-exists --force "
            + DATABASE
            + " && createdb -U \"$POSTGRES_USER\" "
            + DATABASE);

    banner("2/5 the repository the runs clone and publish to (main at phase-7)");
    ScenarioRunner.deleteTree(work);
    Files.createDirectories(work);
    Path remote = work.resolve("remote.git");
    String base = ScenarioRunner.bareCopy(root, remote, "phase-7");
    out.println("  " + base + " -> " + remote);

    banner("3/5 the orchestrator app on " + url + " (replay mode, no API key)");
    Process app = startApp(root, work, remote, operatorKey, approverKey);
    List<String> failed = new ArrayList<>();
    try {
      long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
      while (!operator.healthy()) {
        if (!app.isAlive() || System.nanoTime() > deadline) {
          throw new IllegalStateException(
              "the app did not start; see "
                  + root.relativize(work.resolve("orchestrator-app.log")));
        }
        Thread.sleep(2_000);
      }
      out.println("  up (log: " + root.relativize(work.resolve("orchestrator-app.log")) + ")");

      banner("4/5 the three scenarios, through the API");
      for (String id : SCENARIOS) {
        resetMain(remote, base); // the previous scenario's publish merged into it
        if (!runScenario(root, id, operator, approver, perScenario)) {
          failed.add(id);
        }
      }

      banner("5/5 metrics summary (GET /api/metrics/summary)");
      out.println(
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(operator.get("/api/metrics/summary")));
    } finally {
      app.descendants().forEach(ProcessHandle::destroyForcibly);
      app.destroyForcibly();
    }
    out.println(
        failed.isEmpty()
            ? "\nOK: all three scenarios completed and their audit chains verify."
            : "\nFAILED: " + failed);
    return failed.isEmpty() ? 0 : 1;
  }

  private boolean runScenario(
      Path root, String id, OrchestratorApi operator, OrchestratorApi approver, Duration timeout)
      throws Exception {
    Scenario scenario = Scenario.load(root.resolve("scenarios"), id);
    String runId =
        operator
            .post(
                "/api/runs",
                Map.of(
                    "scenarioId", id,
                    "mode", scenario.mode().name(),
                    "autonomyLevel", scenario.autonomy().name()))
            .path("run")
            .path("id")
            .asString();
    out.printf("%n>> %s  (%s, %s): run %s%n", id, scenario.mode(), scenario.autonomy(), runId);
    ApiHuman human =
        new ApiHuman(scenario, runId, operator, approver, line -> out.println("  [human] " + line));
    long seen = 0;
    long deadline = System.nanoTime() + timeout.toNanos();
    boolean done = false;
    while (!done && System.nanoTime() < deadline) {
      done = human.step();
      for (JsonNode e : operator.get("/api/runs/" + runId + "/events?afterSeq=" + seen)) {
        seen = e.path("seq").asLong();
        if (SHOWN.contains(e.path("type").asString())) {
          out.printf(
              "  %4d %-18s %-18s %s%n",
              seen,
              e.path("type").asString(),
              e.path("stageId").asString(""),
              abbreviate(
                  e.path("payload")
                      .path("summary")
                      .asString(e.path("payload").path("reason").asString("")),
                  110));
        }
      }
      if (!done) {
        Thread.sleep(1_000);
      }
    }
    JsonNode audit = operator.get("/api/runs/" + runId + "/audit/verify");
    boolean ok = human.status().equals(scenario.expect()) && audit.path("valid").asBoolean();
    out.printf(
        "%s %s ended %s (expected %s); audit %s, %d events, head %s%n",
        ok ? "OK" : "FAILED",
        id,
        human.status().isEmpty() ? "(timed out)" : human.status(),
        scenario.expect(),
        audit.path("valid").asBoolean() ? "valid" : "BROKEN",
        audit.path("eventCount").asInt(),
        audit.path("headHash").asString("").substring(0, 12));
    return ok;
  }

  private Process startApp(
      Path root, Path work, Path remote, String operatorKey, String approverKey)
      throws IOException {
    boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    List<String> command = new ArrayList<>();
    if (windows) {
      command.addAll(List.of("cmd.exe", "/c", root.resolve("mvnw.cmd").toString()));
    } else {
      command.add(root.resolve("mvnw").toString());
    }
    command.addAll(List.of("-q", "-pl", "orchestrator-app", "spring-boot:run"));
    ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
    Map<String, String> childEnv = builder.environment();
    childEnv.remove("ANTHROPIC_API_KEY"); // replay only
    childEnv.putAll(appEnvironment(work, remote, operatorKey, approverKey, url.getPort()));
    builder.redirectErrorStream(true);
    builder.redirectOutput(work.resolve("orchestrator-app.log").toFile());
    return builder.start();
  }

  /** The app's settings for the demo: replay, the demo database and repository, one-off keys. */
  static Map<String, String> appEnvironment(
      Path work, Path remote, String operatorKey, String approverKey, int port) {
    Map<String, String> vars = new HashMap<>(POSTGRES); // real env vars beat the app's .env import
    vars.put("ORCH_LLM_MODE", "replay");
    vars.put("ORCH_POSTGRES_URL", "jdbc:postgresql://localhost:" + POSTGRES_PORT + "/" + DATABASE);
    vars.put("ORCH_SOURCE_REPO", remote.toAbsolutePath().toString());
    vars.put("ORCH_BASE_BRANCH", "main");
    vars.put("ORCH_WORKSPACE_ROOT", work.resolve("worktrees").toAbsolutePath().toString());
    vars.put("ORCH_DATA_DIR", work.resolve("data").toAbsolutePath().toString());
    vars.put("ORCH_OPERATOR_KEYS", "bob:" + Hashing.sha256Hex(operatorKey));
    vars.put("ORCH_APPROVER_KEYS", "alice:" + Hashing.sha256Hex(approverKey));
    vars.put("SERVER_PORT", Integer.toString(port));
    return vars;
  }

  /** Moves the bare repository's main back to {@code commit}. */
  static void resetMain(Path bare, String commit) throws IOException {
    try (Git git = Git.open(bare.toFile())) {
      RefUpdate main = git.getRepository().updateRef("refs/heads/main");
      main.setNewObjectId(ObjectId.fromString(commit));
      main.setForceUpdate(true);
      RefUpdate.Result result = main.update();
      if (result != RefUpdate.Result.FORCED
          && result != RefUpdate.Result.NO_CHANGE
          && result != RefUpdate.Result.NEW) {
        throw new IOException("could not reset main of " + bare + ": " + result);
      }
    }
  }

  /** {@code docker compose -p orchestrator-demo …} with the demo's Postgres settings. */
  private void compose(Path dir, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", COMPOSE_PROJECT));
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    builder.environment().putAll(POSTGRES);
    exec(builder, command);
  }

  private void exec(ProcessBuilder builder, List<String> command)
      throws IOException, InterruptedException {
    Process p = builder.redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed:\n" + output.strip());
    }
  }

  private void banner(String title) {
    out.println("\n=== " + title + " " + "=".repeat(Math.max(3, 74 - title.length())));
  }

  private static String randomKey() {
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    return "demo-" + HexFormat.of().formatHex(bytes);
  }

  private static String abbreviate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }
}
