package com.acme.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.orchestrator.cli.Scenario;
import com.acme.orchestrator.cli.Scenario.ScriptedDecision;
import com.acme.orchestrator.core.model.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the scenario end-to-end tests share: the three Phase 7 scenarios end to end, through the
 * HTTP API on real PostgreSQL, with the real agents replaying the recorded fixtures, real Git
 * workspaces and a real Maven build of the shortener in each run. The humans are played over the
 * API as each {@code scenario.yml} says. Runs only with {@code -Pe2e} (about 10 minutes).
 *
 * <p>Every run clones from a bare copy of this repository whose {@code main} is reset to the
 * scenario's base first: the previous scenario's publish merged into it.
 */
abstract class ScenarioE2ESupport {

  static final String OPERATOR = "e2e-operator-key";
  static final String APPROVER = "e2e-approver-key";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Path REPO = Path.of("..").toAbsolutePath().normalize();
  static final Path SOURCE;

  static {
    try {
      SOURCE = Files.createTempDirectory("e2e-source").resolve("remote.git");
      Git.cloneRepository()
          .setURI(REPO.toUri().toString())
          .setDirectory(SOURCE.toFile())
          .setBare(true)
          .call()
          .close();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void wiring(DynamicPropertyRegistry registry) throws Exception {
    registry.add("orchestrator.operator-keys", () -> "bob:" + Hashing.sha256Hex(OPERATOR));
    registry.add("orchestrator.approver-keys", () -> "alice:" + Hashing.sha256Hex(APPROVER));
    registry.add("orchestrator.scenarios-dir", () -> REPO.resolve("scenarios").toString());
    registry.add("orchestrator.config-dir", () -> REPO.resolve("config").toString());
    registry.add("orchestrator.source-repo", SOURCE::toString);
    Path work = Files.createTempDirectory("e2e-work");
    registry.add("orchestrator.workspace-root", () -> work.resolve("worktrees").toString());
    registry.add("orchestrator.data-dir", () -> work.resolve("data").toString());
  }

  @Autowired MockMvc mvc;

  /** The last validation summary of the latest run ("build SUCCESS (sandboxed), …"). */
  String lastValidationSummary = "";

  /** What each scenario must show besides finishing as expected. */
  static final Map<String, List<String>> CONTROL_EVENTS =
      Map.of(
          "greenfield-qr-code",
          List.of("APPROVAL_REQUESTED", "APPROVAL_GRANTED", "STAGE_SKIPPED", "RUN_COMPLETED"),
          "brownfield-expiry-bf1",
          List.of(
              "APPROVAL_REJECTED",
              "STAGE_INVALIDATED",
              "FALLBACK_USED",
              "APPROVAL_GRANTED",
              "RUN_COMPLETED"),
          "ambiguous-abuse",
          List.of("REPLAN_TRIGGERED", "STAGE_INVALIDATED", "APPROVAL_GRANTED", "RUN_COMPLETED"),
          "greenfield-fanout-notes",
          List.of("STAGE_EXPANDED", "APPROVAL_GRANTED", "RUN_COMPLETED"));

  /** Runs the scenario through the API, plays its humans, checks the outcome; the metrics JSON. */
  JsonNode runEndToEnd(String id) throws Exception {
    Scenario scenario = Scenario.load(REPO.resolve("scenarios"), id);
    resetSourceTo(scenario.base());
    String runId =
        call(
                post("/api/runs")
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "scenarioId", id,
                                "mode", scenario.mode().name(),
                                "autonomyLevel", scenario.autonomy().name(),
                                "workflow", scenario.workflow()))),
                OPERATOR,
                201)
            .path("run")
            .path("id")
            .asString();

    ApiHuman human = new ApiHuman(scenario, runId);
    await()
        .atMost(Duration.ofMinutes(20))
        .pollInterval(Duration.ofSeconds(1))
        .until(human::actUntilDone);

    List<String> failures = new ArrayList<>();
    call(get("/api/runs/" + runId + "/events"), OPERATOR, 200)
        .forEach(e -> {
          if (e.path("type").asString().endsWith("_FAILED")) {
            failures.add(
                e.path("stageId").asString("run") + ": " + e.path("payload").path("reason"));
          }
        });
    assertThat(human.status)
        .as(id + " ends as the scenario expects; failures: " + failures)
        .isEqualTo(scenario.expect());
    List<String> types = new ArrayList<>();
    call(get("/api/runs/" + runId + "/events"), OPERATOR, 200)
        .forEach(e -> types.add(e.path("type").asString()));
    assertThat(types).containsAll(CONTROL_EVENTS.get(id));
    java.util.regex.Matcher build =
        java.util.regex.Pattern.compile("build (SUCCESS|FAILURE|TIMED OUT)[^\"]*")
            .matcher(call(get("/api/runs/" + runId + "/events"), OPERATOR, 200).toString());
    while (build.find()) {
      lastValidationSummary = build.group();
    }
    if (id.equals("brownfield-expiry-bf1")) {
      assertThat(
              call(get("/api/runs/" + runId + "/events"), OPERATOR, 200)
                  .valueStream()
                  .filter(e -> e.path("type").asString().equals("POLICY_EVALUATED"))
                  .map(e -> e.path("payload").path("effect").asString()))
          .as("the path-jail DENY behind the fallback")
          .contains("DENY");
    }
    if (id.equals("ambiguous-abuse")) {
      assertThat(types.stream().filter("REPLAN_TRIGGERED"::equals))
          .hasSize(2); // answers, amendment
    }
    if (id.equals("greenfield-fanout-notes")) {
      assertFanOut(call(get("/api/runs/" + runId + "/events"), OPERATOR, 200));
    }
    JsonNode audit = call(get("/api/runs/" + runId + "/audit/verify"), OPERATOR, 200);
    assertThat(audit.path("valid").asBoolean()).as("the hash chain verifies").isTrue();
    JsonNode metrics = call(get("/api/metrics/summary"), OPERATOR, 200);
    return metrics;
  }

  /**
   * The implementation ran as three tasks: T1 and T2 at the same time, T3 only after both (T1 is
   * its declared dependency, T2 shares openapi.yaml with it).
   */
  private static void assertFanOut(JsonNode events) {
    Map<String, Long> started = new HashMap<>();
    Map<String, Long> succeeded = new HashMap<>();
    events.forEach(e -> {
      String stage = e.path("stageId").asString("");
      long seq = e.path("seq").asLong();
      switch (e.path("type").asString()) {
        case "STAGE_STARTED" -> started.putIfAbsent(stage, seq);
        // an approved stage succeeds with the approval (T1's migration needs one)
        case "STAGE_SUCCEEDED", "APPROVAL_GRANTED" -> succeeded.put(stage, seq);
        default -> {}
      }
    });
    assertThat(started)
        .containsKeys("implementation.T1", "implementation.T2", "implementation.T3")
        .doesNotContainKey("implementation");
    assertThat(started.get("implementation.T2"))
        .as("T2 starts while T1 is still running")
        .isLessThan(succeeded.get("implementation.T1"));
    assertThat(started.get("implementation.T3"))
        .as("T3 waits for T1 and T2")
        .isGreaterThan(succeeded.get("implementation.T1"))
        .isGreaterThan(succeeded.get("implementation.T2"));
  }

  /** Plays the scenario's humans over the API: answers, approvals, rejections, the amendment. */
  final class ApiHuman {
    private final Scenario scenario;
    private final String runId;
    private final Set<String> handled = new HashSet<>();
    private final Map<String, Integer> decided = new HashMap<>();
    private boolean answered;
    private boolean amended;
    String status;

    ApiHuman(Scenario scenario, String runId) {
      this.scenario = scenario;
      this.runId = runId;
    }

    /** One look at the run; acts if it waits for a human. True once it has finished. */
    boolean actUntilDone() throws Exception {
      status = call(get("/api/runs/" + runId), OPERATOR, 200).path("run").path("status").asString();
      if (List.of("COMPLETED", "FAILED", "CANCELLED", "HALTED").contains(status)) {
        return true;
      }
      if (!status.equals("AWAITING_HUMAN")) {
        return false;
      }
      JsonNode approvals = call(get("/api/approvals?runId=" + runId), OPERATOR, 200);
      if (scenario.amend().isPresent()
          && !amended
          && granted(approvals, scenario.amend().get().afterApprovalOf())) {
        amended = true;
        call(
            post("/api/runs/" + runId + "/requirements")
                .content(JSON.writeValueAsString(Map.of("text", scenario.amend().get().text()))),
            OPERATOR,
            202);
        return false;
      }
      for (JsonNode a : approvals) {
        if (!a.path("status").asString().equals("PENDING")
            || !handled.add(a.path("id").asString())) {
          continue;
        }
        if (a.path("reason").asString().startsWith("human input needed")
            && scenario.answersJson().isPresent()
            && !answered) {
          answered = true;
          call(
              post("/api/runs/" + runId + "/answers").content(scenario.answersJson().get()),
              OPERATOR,
              202);
          continue;
        }
        ScriptedDecision d = next(a.path("stageId").asString());
        call(
            post("/api/approvals/" + a.path("id").asString())
                .content(
                    JSON.writeValueAsString(
                        Map.of("decision", d.decision().name(), "comment", d.comment()))),
            APPROVER,
            202);
      }
      return false;
    }

    private ScriptedDecision next(String stage) {
      int n = decided.merge(stage, 1, Integer::sum);
      List<ScriptedDecision> forStage =
          scenario.decisions().stream().filter(d -> d.stage().equals(stage)).toList();
      return n <= forStage.size()
          ? forStage.get(n - 1)
          : new ScriptedDecision(
              stage,
              com.acme.orchestrator.core.engine.Command.ApprovalDecision.Decision.APPROVE,
              "");
    }

    private static boolean granted(JsonNode approvals, String stage) {
      for (JsonNode a : approvals) {
        if (a.path("stageId").asString().equals(stage)
            && a.path("status").asString().equals("GRANTED")) {
          return true;
        }
      }
      return false;
    }
  }

  private JsonNode call(MockHttpServletRequestBuilder request, String key, int expected)
      throws Exception {
    String body =
        mvc.perform(request.header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body.isEmpty() ? "{}" : body);
  }

  /** Resets the bare source's main to the scenario's base commit in this repository. */
  private static void resetSourceTo(String base) throws Exception {
    ObjectId commit;
    try (Git repo = Git.open(REPO.toFile())) {
      commit = repo.getRepository().resolve(base + "^{commit}");
    }
    try (Git bare = Git.open(SOURCE.toFile())) {
      Repository r = bare.getRepository();
      RefUpdate main = r.updateRef("refs/heads/main");
      main.setNewObjectId(commit);
      main.setForceUpdate(true);
      main.update();
    }
  }
}
