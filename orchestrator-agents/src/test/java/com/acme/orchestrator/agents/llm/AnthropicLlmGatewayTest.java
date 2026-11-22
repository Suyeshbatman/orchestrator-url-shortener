package com.acme.orchestrator.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Against a local fake of the Messages API, so the real SDK, retries and fallback are exercised.
 */
class AnthropicLlmGatewayTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LlmRequest REQUEST =
      LlmRequest.of("RequirementsAgent", "You write specs.", "Add QR codes.", 800)
          .withSchema("{\"type\":\"object\"}");

  private record Reply(int status, String body) {}

  private final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
  private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());
  private HttpServer server;
  private AnthropicLlmGateway gateway;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/messages",
        exchange -> {
          received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
          Reply reply =
              script.isEmpty()
                  ? new Reply(500, error("api_error", "no script"))
                  : script.removeFirst();
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (gateway != null) {
      gateway.close();
    }
    server.stop(0);
  }

  private AnthropicLlmGateway gateway(Optional<String> fallback) {
    gateway =
        new AnthropicLlmGateway(
            "test-key",
            Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
            new AnthropicLlmGateway.Settings(
                "claude-sonnet-5-5", fallback, Duration.ofSeconds(5), 3, Duration.ofMillis(10)));
    return gateway;
  }

  private static String message(String model, String text) {
    return """
    {"id": "msg_1", "type": "message", "role": "assistant", "model": "%s",
     "content": [{"type": "text", "text": %s}],
     "stop_reason": "end_turn", "stop_sequence": null,
     "usage": {"input_tokens": 120, "output_tokens": 45}}
    """
        .formatted(model, JSON.writeValueAsString(text));
  }

  private static String error(String type, String message) {
    return "{\"type\": \"error\", \"error\": {\"type\": \"%s\", \"message\": \"%s\"}}"
        .formatted(type, message);
  }

  @Test
  void aReplyIsMappedAndTheSchemaIsSentWithTheSystemPrompt() {
    script.add(new Reply(200, message("claude-sonnet-5-5", "{\"ok\":true}")));

    LlmResponse response = gateway(Optional.empty()).complete(REQUEST);

    assertThat(response.text()).isEqualTo("{\"ok\":true}");
    assertThat(response.inputTokens()).isEqualTo(120);
    assertThat(response.outputTokens()).isEqualTo(45);
    assertThat(response.model()).isEqualTo("claude-sonnet-5-5");
    JsonNode sent = received.getFirst();
    assertThat(sent.path("model").asString()).isEqualTo("claude-sonnet-5-5");
    assertThat(sent.path("max_tokens").asInt()).isEqualTo(800);
    assertThat(sent.path("system").toString())
        .contains("You write specs.")
        .contains("validates against this JSON Schema");
    assertThat(sent.path("messages").get(0).path("content").toString()).contains("Add QR codes.");
    assertThat(sent.has("temperature")).isFalse();
  }

  @Test
  void largeRepliesGetMoreTime() {
    var settings = AnthropicLlmGateway.Settings.of("m", Optional.empty());

    assertThat(settings.timeoutFor(0)).isEqualTo(Duration.ofSeconds(60));
    assertThat(settings.timeoutFor(32_000)).isEqualTo(Duration.ofSeconds(60 + 800));
  }

  @Test
  void rateLimitsAndServerErrorsAreRetried() {
    script.add(new Reply(429, error("rate_limit_error", "slow down")));
    script.add(new Reply(529, error("overloaded_error", "overloaded")));
    script.add(new Reply(200, message("claude-sonnet-5-5", "third time lucky")));

    assertThat(gateway(Optional.empty()).complete(REQUEST).text()).isEqualTo("third time lucky");
    assertThat(received).hasSize(3);
  }

  @Test
  void whenThePrimaryModelStaysDownTheFallbackModelIsTried() {
    for (int i = 0; i < 3; i++) {
      script.add(new Reply(503, error("api_error", "unavailable")));
    }
    script.add(new Reply(200, message("claude-haiku-5-5", "from the fallback")));

    LlmResponse response = gateway(Optional.of("claude-haiku-5-5")).complete(REQUEST);

    assertThat(response.text()).isEqualTo("from the fallback");
    assertThat(response.model()).isEqualTo("claude-haiku-5-5");
    assertThat(received)
        .extracting(r -> r.path("model").asString())
        .containsExactly(
            "claude-sonnet-5-5", "claude-sonnet-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");
  }

  @Test
  void withoutAFallbackAnOutageIsTransient() {
    for (int i = 0; i < 3; i++) {
      script.add(new Reply(500, error("api_error", "boom")));
    }

    assertThatThrownBy(() -> gateway(Optional.empty()).complete(REQUEST))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("claude-sonnet-5-5 unavailable after 3 attempts");
  }

  @Test
  void aRejectedRequestIsNotRetried() {
    script.add(new Reply(401, error("authentication_error", "invalid x-api-key")));

    assertThatThrownBy(() -> gateway(Optional.of("claude-haiku-5-5")).complete(REQUEST))
        .isInstanceOf(IllegalStateException.class) // fatal: a bad key won't fix itself
        .hasMessageContaining("HTTP 401");
    assertThat(received).hasSize(1);
  }

  @Test
  void aTemperatureIsSentWhenSet() {
    script.add(new Reply(200, message("claude-sonnet-5-5", "ok")));
    LlmRequest warm =
        new LlmRequest(
            "A",
            "s",
            "u",
            Optional.empty(),
            10,
            java.util.OptionalDouble.of(0.3),
            Optional.empty());

    gateway(Optional.empty()).complete(warm);

    assertThat(received.getFirst().path("temperature").asDouble()).isEqualTo(0.3);
    assertThat(received.getFirst().path("system").toString()).doesNotContain("JSON Schema");
  }
}
