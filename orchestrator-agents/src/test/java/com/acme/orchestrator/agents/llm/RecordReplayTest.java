package com.acme.orchestrator.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayTest {

  private static final LlmRequest REQUEST =
      LlmRequest.of("RequirementsAgent", "You write specs.", "Add QR codes.", 1000)
          .withSchema("{\"type\":\"object\"}");

  @TempDir Path dir;

  private static LlmResponse reply(String text) {
    return new LlmResponse(
        text, Optional.empty(), 120, 45, "claude-sonnet-5-5", Duration.ofMillis(800));
  }

  @Test
  void theHashCoversAgentPromptsAndSchemaButNotTuning() {
    assertThat(REQUEST.hash()).matches("[0-9a-f]{64}");
    assertThat(REQUEST.hash())
        .isEqualTo(
            new LlmRequest(
                    "RequirementsAgent",
                    "You write specs.",
                    "Add QR codes.",
                    REQUEST.jsonSchema(),
                    50,
                    java.util.OptionalDouble.of(0.2),
                    Optional.of("T1")) // neither sampling nor the task is part of the key
                .hash());
    assertThat(REQUEST.withPrompts("You write specs.", "Add QR codes!").hash())
        .isNotEqualTo(REQUEST.hash());
    assertThat(LlmRequest.of("RequirementsAgent", "You write specs.", "Add QR codes.", 1000).hash())
        .as("the schema counts")
        .isNotEqualTo(REQUEST.hash());
    assertThatThrownBy(() -> LlmRequest.of("A", "s", "u", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordedRepliesReplayWithoutTheModel() throws Exception {
    AtomicInteger liveCalls = new AtomicInteger();
    LlmGateway live =
        request -> {
          liveCalls.incrementAndGet();
          return reply("{\"problemStatement\":\"QR\"}");
        };

    LlmResponse recorded = new RecordingLlmGateway(live, dir).complete(REQUEST);
    LlmResponse replayed = new ReplayLlmGateway(dir).complete(REQUEST);

    assertThat(liveCalls).hasValue(1);
    assertThat(replayed.text()).isEqualTo(recorded.text());
    assertThat(replayed.inputTokens()).isEqualTo(120);
    assertThat(replayed.outputTokens()).isEqualTo(45);
    assertThat(replayed.model()).isEqualTo("claude-sonnet-5-5");
    assertThat(replayed.latency()).isEqualTo(Duration.ofMillis(800));

    // the fixture is readable for review: the request, the reply, the hash it answers
    String fixture = Files.readString(dir.resolve(REQUEST.hash() + ".json"));
    assertThat(fixture)
        .contains("\"requestHash\" : \"" + REQUEST.hash() + "\"")
        .contains("\"user\" : \"Add QR codes.\"")
        .contains("\"schema\"")
        .endsWith("\n");
    try (var files = Files.list(dir)) {
      assertThat(files).hasSize(1); // no temp files left behind
    }
  }

  @Test
  void aMissingFixtureSaysHowToRecordIt() {
    assertThatThrownBy(() -> new ReplayLlmGateway(dir).complete(REQUEST))
        .isInstanceOf(ReplayMissingException.class)
        .isInstanceOf(IllegalStateException.class) // fatal for the engine: no point retrying
        .hasMessageContaining("no recorded LLM reply for RequirementsAgent")
        .hasMessageContaining(REQUEST.hash())
        .hasMessageContaining("ORCH_LLM_MODE=record");
  }

  @Test
  void aFixtureFileForAnotherRequestIsRejected() throws Exception {
    new RecordingLlmGateway(request -> reply("x"), dir).complete(REQUEST);
    LlmRequest other = REQUEST.withPrompts("other", "other");
    Files.move(dir.resolve(REQUEST.hash() + ".json"), dir.resolve(other.hash() + ".json"));

    assertThatThrownBy(() -> new ReplayLlmGateway(dir).complete(other))
        .hasMessageContaining("is not the fixture for request " + other.hash());
  }

  @Test
  void secretsAreScrubbedBeforeTheyReachTheModelOrAFixture() throws Exception {
    String key = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz";
    SecretRedactor redactor = SecretRedactor.fromEnvironment(Map.of("ANTHROPIC_API_KEY", key));
    LlmRequest leaky = LlmRequest.of("ImplementationAgent", "system", "the key is " + key, 100);
    AtomicInteger seen = new AtomicInteger();
    LlmGateway live =
        request -> {
          assertThat(request.userPrompt()).doesNotContain(key).contains(SecretRedactor.REDACTED);
          seen.incrementAndGet();
          return reply("ok");
        };

    new RedactingLlmGateway(new RecordingLlmGateway(live, dir), redactor).complete(leaky);

    assertThat(seen).hasValue(1);
    try (var files = Files.list(dir)) {
      String fixture = Files.readString(files.findFirst().orElseThrow());
      assertThat(fixture).doesNotContain(key);
    }
    // replaying the same (unredacted) request finds the fixture: the hash is of the scrubbed text
    assertThat(new RedactingLlmGateway(new ReplayLlmGateway(dir), redactor).complete(leaky).text())
        .isEqualTo("ok");
  }
}
