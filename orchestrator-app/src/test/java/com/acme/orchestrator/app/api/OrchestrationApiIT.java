package com.acme.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.orchestrator.app.TestcontainersConfiguration;
import com.acme.orchestrator.app.run.ActiveRuns;
import com.acme.orchestrator.app.run.RunLauncher;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentRegistry;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.ArtifactOutput;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.engine.GateEvaluator;
import com.acme.orchestrator.core.engine.GateResult;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.gate.SchemaValidator;
import com.acme.orchestrator.core.model.Hashing;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API over HTTP (MockMvc) on real PostgreSQL, driving the standard workflow with fake agents
 * through every human checkpoint.
 */
@Import({TestcontainersConfiguration.class, OrchestrationApiIT.FakeAgents.class})
@SpringBootTest(properties = "orchestrator.recover-on-startup=false")
@AutoConfigureMockMvc
class OrchestrationApiIT {

  static final String OPERATOR = "operator-secret-key";
  static final String APPROVER = "approver-secret-key";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Duration WAIT = Duration.ofSeconds(20);

  @DynamicPropertySource
  static void keysAndScenarios(DynamicPropertyRegistry registry) throws Exception {
    registry.add("orchestrator.operator-keys", () -> "bob:" + Hashing.sha256Hex(OPERATOR));
    registry.add("orchestrator.approver-keys", () -> "alice:" + Hashing.sha256Hex(APPROVER));
    Path scenarios = Files.createTempDirectory("scenarios");
    Files.createDirectories(scenarios.resolve("qr-codes"));
    Files.writeString(scenarios.resolve("qr-codes/requirement.md"), "Add QR codes for links.\n");
    registry.add("orchestrator.scenarios-dir", scenarios::toString);
  }

  /** The standard workflow with fake agents; the requirements agent asks until it has answers. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FakeAgents {
    @Bean
    @Primary
    RunLauncher fakeLauncher(EventStore events, ArtifactStore artifacts, ActiveRuns active) {
      var workflow =
          new WorkflowLoader().load(Path.of("..", "config", "workflows", "sdlc-standard.yml"));
      GateRegistry real = GateRegistry.standard(SchemaValidator.STRUCTURE_ONLY);
      GateEvaluator gates =
          (gate, ctx) ->
              gate.name().equals("spec.noBlockingQuestions")
                  ? real.evaluate(gate, ctx)
                  : GateResult.pass("fake");
      Agent requirements =
          ctx -> {
            ctx.requireArtifact("requirement");
            String spec =
                ctx.artifact("answers").isPresent()
                    ? "{\"ambiguities\":[]}"
                    : "{\"ambiguities\":[{\"id\":\"AMB-1\",\"question\":\"PNG or SVG?\",\"severity\":\"BLOCKING\"}]}";
            return AgentResult.success(
                "spec", ArtifactOutput.of("requirements_spec", spec, "fake"));
          };
      Agent other =
          ctx ->
              AgentResult.success(
                  ctx.stageKey(), ArtifactOutput.of(ctx.stageKey(), "{\"ok\":true}", "fake"));
      Map<String, Agent> agents = new HashMap<>();
      for (String name :
          List.of(
              "DecompositionAgent",
              "ImpactAnalysisAgent",
              "DesignAgent",
              "ImplementationAgent",
              "TestAuthoringAgent",
              "DocsAgent",
              "ValidationAgent",
              "ReleaseAgent",
              "git.mergeToMain")) {
        agents.put(name, other);
      }
      agents.put("RequirementsAgent", requirements);
      return (row, run) -> {
        Coordinator c =
            Coordinator.start(
                workflow,
                run,
                new Coordinator.Services(
                    events, artifacts, AgentRegistry.of(agents), gates, ToolRegistry.EMPTY));
        active.put(c);
        return c;
      };
    }
  }

  @Autowired MockMvc mvc;

  private ResultActions call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String key)
      throws Exception {
    return mvc.perform(key == null ? request : request.header("X-API-Key", key));
  }

  private JsonNode json(ResultActions result) throws Exception {
    return JSON.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private String createRun(String body) throws Exception {
    JsonNode run =
        json(
            call(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body), OPERATOR)
                .andExpect(status().isCreated()));
    return run.path("run").path("id").asString();
  }

  private JsonNode run(String id) throws Exception {
    return json(call(get("/api/runs/" + id), OPERATOR).andExpect(status().isOk()));
  }

  private String stageStatus(String run, String stage) throws Exception {
    for (JsonNode n : run(run).path("nodes")) {
      if (n.path("id").asString().equals(stage)) {
        return n.path("status").asString();
      }
    }
    throw new AssertionError("no stage " + stage);
  }

  /** The pending approval of {@code stage}, once there is one. */
  private JsonNode pending(String run, String stage) {
    return await()
        .atMost(WAIT)
        .until(
            () -> {
              for (JsonNode a :
                  json(call(get("/api/approvals?status=pending&runId=" + run), OPERATOR))) {
                if (a.path("stageId").asString().equals(stage)) {
                  return a;
                }
              }
              return null;
            },
            a -> a != null);
  }

  private void decide(JsonNode approval, String decision) throws Exception {
    call(
            post("/api/approvals/" + approval.path("id").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"comment\":\"ok\"}"),
            APPROVER)
        .andExpect(status().isAccepted());
  }

  // ---- authentication and roles ---------------------------------------------------------------

  @Test
  void everyCallNeedsAValidKey() throws Exception {
    call(get("/api/runs"), null)
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-API-Key\""))
        .andExpect(jsonPath("$.type").value("/problems/unauthorized"));
    call(get("/api/runs"), "not-a-key").andExpect(status().isUnauthorized());
    call(get("/api/runs"), OPERATOR).andExpect(status().isOk());
    call(get("/api/runs"), APPROVER).andExpect(status().isOk()); // anyone may read
  }

  @Test
  void anOperatorCannotApproveAndAnApproverCannotSteer() throws Exception {
    String id = createRun("{\"requirementText\":\"QR codes\",\"mode\":\"GREENFIELD\"}");
    JsonNode questions = pending(id, "requirements");

    call(
            post("/api/approvals/" + questions.path("id").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"),
            OPERATOR)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("/problems/forbidden"))
        .andExpect(jsonPath("$.detail").value("bob does not have the APPROVER role"));
    call(post("/api/runs/" + id + "/halt"), APPROVER).andExpect(status().isForbidden());
    call(
            post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementText\":\"x\",\"mode\":\"GREENFIELD\"}"),
            APPROVER)
        .andExpect(status().isForbidden());
  }

  // ---- validation -----------------------------------------------------------------------------

  @Test
  void badRequestsGetProblemDetails() throws Exception {
    for (String body :
        List.of(
            "{\"mode\":\"GREENFIELD\"}",
            "{\"requirementText\":\"x\",\"scenarioId\":\"qr-codes\",\"mode\":\"GREENFIELD\"}",
            "{\"requirementText\":\"x\"}",
            "{\"scenarioId\":\"../../etc\",\"mode\":\"GREENFIELD\"}")) {
      call(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body), OPERATOR)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("/problems/validation-failed"));
    }
    call(
            post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SIDEWAYS\"}"),
            OPERATOR)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/malformed-request"));
    call(
            post("/api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenarioId\":\"nope\",\"mode\":\"GREENFIELD\"}"),
            OPERATOR)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("/problems/scenario-not-found"));
    call(get("/api/runs/run-missing"), OPERATOR)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("/problems/run-not-found"));
    call(get("/api/artifacts/nope"), OPERATOR)
        .andExpect(jsonPath("$.type").value("/problems/artifact-not-found"));
    call(
            post("/api/approvals/nope")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"),
            APPROVER)
        .andExpect(jsonPath("$.type").value("/problems/approval-not-found"));
  }

  // ---- a whole run ----------------------------------------------------------------------------

  @Test
  void aScenarioRunGoesThroughEveryHumanCheckpointOverHttp() throws Exception {
    String id =
        createRun(
            "{\"scenarioId\":\"qr-codes\",\"mode\":\"GREENFIELD\",\"autonomyLevel\":\"L1_SUPERVISED\"}");

    JsonNode view = run(id);
    assertThat(view.path("run").path("scenarioId").asString()).isEqualTo("qr-codes");
    assertThat(view.path("nodes")).hasSize(11);
    assertThat(view.path("edges").toString())
        .contains("{\"from\":\"validation\",\"to\":\"docs_finalize\"}");

    // the spec has a blocking question: the run waits for answers
    JsonNode questions = pending(id, "requirements");
    assertThat(questions.path("summary").path("needsInput").asBoolean()).isTrue();
    assertThat(questions.path("reason").asString()).contains("AMB-1: PNG or SVG?");
    call(
            post("/api/runs/" + id + "/answers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[{\"ambiguityId\":\"AMB-1\",\"answer\":\"PNG only\"}]}"),
            OPERATOR)
        .andExpect(status().isAccepted());

    // spec v2 has no open questions: scope sign-off, then design, release, publish
    JsonNode scope =
        await()
            .atMost(WAIT)
            .until(
                () -> pending(id, "requirements"),
                a -> !a.path("id").asString().equals(questions.path("id").asString()));
    assertThat(scope.path("reason").asString()).isEqualTo("Scope sign-off");
    decide(scope, "APPROVE");
    decide(pending(id, "design"), "APPROVE");
    decide(pending(id, "release_readiness"), "APPROVE");
    JsonNode merge = pending(id, "publish");
    assertThat(merge.path("summary").path("before").asBoolean()).isTrue();
    decide(merge, "APPROVE");
    await()
        .atMost(WAIT)
        .until(() -> run(id).path("run").path("status").asString(), "COMPLETED"::equals);

    // what happened is all there, and verifiable
    call(get("/api/runs/" + id + "/audit/verify"), OPERATOR)
        .andExpect(jsonPath("$.valid").value(true));
    JsonNode events = json(call(get("/api/runs/" + id + "/events"), OPERATOR));
    assertThat(events.get(0).path("type").asString()).isEqualTo("RUN_CREATED");
    long last = events.get(events.size() - 1).path("seq").asLong();
    assertThat(json(call(get("/api/runs/" + id + "/events?afterSeq=" + (last - 1)), OPERATOR)))
        .hasSize(1);
    assertThat(events.toString())
        .contains("\"actor\":\"human:alice\"")
        .contains("\"actor\":\"human:bob\"");

    JsonNode artifacts = json(call(get("/api/runs/" + id + "/artifacts"), OPERATOR));
    String specV2 = id + ":requirements:requirements_spec:v2";
    assertThat(artifacts.toString())
        .contains(id + ":input:requirement:v1", id + ":input:answers:v1", specV2);
    call(get("/api/artifacts/" + id + ":input:requirement:v1"), OPERATOR)
        .andExpect(jsonPath("$.content.text").value("Add QR codes for links."))
        .andExpect(jsonPath("$.artifact.producedBy").value("human:bob"));
    JsonNode lineage = json(call(get("/api/artifacts/" + specV2 + "/lineage"), OPERATOR));
    assertThat(lineage.toString()).contains(id + ":input:answers:v1", id + ":input:requirement:v1");

    call(get("/api/runs/" + id + "/dag.mmd"), OPERATOR)
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("flowchart LR")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "publish[\"publish<br/>SUCCEEDED\"]:::succeeded")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("design --> implementation")));

    // a decided approval and a finished run take no more commands
    call(
            post("/api/approvals/" + merge.path("id").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"REJECT\"}"),
            APPROVER)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/approval-not-pending"));
    call(post("/api/runs/" + id + "/halt"), OPERATOR)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/run-not-active"));
  }

  @Test
  void aRunCanBeHaltedResumedAmendedAndRolledBack() throws Exception {
    String id = createRun("{\"requirementText\":\"QR codes\",\"mode\":\"GREENFIELD\"}");
    pending(id, "requirements");

    call(
            post("/api/runs/" + id + "/halt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"lunch\"}"),
            OPERATOR)
        .andExpect(status().isAccepted());
    await()
        .atMost(WAIT)
        .until(() -> run(id).path("run").path("status").asString(), "HALTED"::equals);
    call(post("/api/runs/" + id + "/resume"), OPERATOR).andExpect(status().isAccepted());
    await()
        .atMost(WAIT)
        .until(() -> run(id).path("run").path("status").asString(), "AWAITING_HUMAN"::equals);

    call(
            post("/api/runs/" + id + "/requirements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"QR codes, PNG and SVG\"}"),
            OPERATOR)
        .andExpect(status().isAccepted());
    await()
        .atMost(WAIT)
        .until(
            () -> json(call(get("/api/runs/" + id + "/events"), OPERATOR)).toString(),
            e -> e.contains("REPLAN_TRIGGERED"));

    call(
            post("/api/runs/" + id + "/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStage\":\"nonsense\"}"),
            OPERATOR)
        .andExpect(status().isBadRequest());
    call(
            post("/api/runs/" + id + "/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toStage\":\"requirements\"}"),
            OPERATOR)
        .andExpect(status().isAccepted());
    call(
            post("/api/runs/" + id + "/requirements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\" \"}"),
            OPERATOR)
        .andExpect(status().isBadRequest());
    assertThat(stageStatus(id, "publish")).isEqualTo("PENDING");
  }

  @Test
  void theOpenApiDescriptionIsPublished() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("Agentic SDLC Orchestrator"))
        .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"));
  }
}
