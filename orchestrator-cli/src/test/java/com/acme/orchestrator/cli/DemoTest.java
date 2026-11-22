package com.acme.orchestrator.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.core.model.Hashing;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DemoTest {

  HttpServer server;
  final List<String> calls = new ArrayList<>();
  URI url;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call =
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI()
                  + " key="
                  + exchange.getRequestHeaders().getFirst("X-API-Key")
                  + " "
                  + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          calls.add(call.strip());
          String path = exchange.getRequestURI().getPath();
          int status;
          String body;
          if (path.equals("/actuator/health")) {
            status = 200;
            body = "{\"status\":\"UP\"}";
          } else if (path.equals("/api/runs/nope/audit/verify")) {
            status = 404;
            body = "{\"detail\":\"no run nope\"}";
          } else {
            status = 202;
            body = "{\"valid\":true}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void theApiClientSendsTheKeyAndReportsProblems() throws Exception {
    OrchestratorApi api = new OrchestratorApi(url, "k1");

    assertThat(api.healthy()).isTrue();
    assertThat(api.as("k2").post("/api/runs", Map.of("a", 1)).path("valid").asBoolean()).isTrue();
    assertThat(calls).contains("POST /api/runs key=k2 {\"a\":1}");
    assertThatThrownBy(() -> api.get("/api/runs/nope/audit/verify"))
        .isInstanceOf(IOException.class)
        .hasMessage("/api/runs/nope/audit/verify answered 404: no run nope");
    assertThat(new OrchestratorApi(URI.create("http://127.0.0.1:1"), "k").healthy()).isFalse();
  }

  @Test
  void theApproverReadsTheReasonSummaryRiskAndDiff() {
    var summary =
        JsonMapper.builder()
            .build()
            .readTree(
                "{\"summary\":\"added expiry\",\"riskScore\":21,"
                    + "\"diffStats\":{\"filesChanged\":3,\"linesChanged\":40}}");

    assertThat(ApiHuman.describe("implementation", "policy: CHG-001", summary))
        .isEqualTo(
            "approval: implementation -- policy: CHG-001\n"
                + "    added expiry\n"
                + "    risk 21, diff 3 files / 40 lines");
  }

  @Test
  void theDemoAppRunsInReplayWithOneOffKeysAndItsOwnDatabaseAndRepository() {
    Map<String, String> vars =
        Demo.appEnvironment(Path.of("work"), Path.of("work", "remote.git"), "op", "ap", 8086);

    assertThat(vars)
        .containsEntry("ORCH_LLM_MODE", "replay")
        .containsEntry("ORCH_POSTGRES_URL", "jdbc:postgresql://localhost:5435/orchestrator_demo")
        .containsEntry("POSTGRES_PASSWORD", "orchestrator-demo-only") // its own database, no .env
        .containsEntry("ORCH_OPERATOR_KEYS", "bob:" + Hashing.sha256Hex("op"))
        .containsEntry("ORCH_APPROVER_KEYS", "alice:" + Hashing.sha256Hex("ap"))
        .containsEntry("SERVER_PORT", "8086");
    assertThat(Path.of(vars.get("ORCH_SOURCE_REPO")).isAbsolute()).isTrue();
    assertThat(vars.get("ORCH_SOURCE_REPO")).endsWith("remote.git");
  }

  @Test
  void theCommandsNeedTheirArguments() throws Exception {
    assertThat(Main.run(List.of())).isEqualTo(2);
    assertThat(Main.run(List.of("verify-audit", "run-1"))).isEqualTo(2); // no --key
    assertThat(Main.run(List.of("metrics"))).isEqualTo(2);
    assertThat(Main.run(List.of("nonsense"))).isEqualTo(2);
    assertThat(Main.run(List.of("verify-audit", "run-1", "--key", "k", "--url", url.toString())))
        .isZero();
    assertThat(calls).contains("GET /api/runs/run-1/audit/verify key=k");
  }
}
