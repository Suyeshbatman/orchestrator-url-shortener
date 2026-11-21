package com.acme.orchestrator.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.llm.PromptTemplates.Prompt;
import com.acme.orchestrator.core.budget.BudgetExceededException;
import com.acme.orchestrator.core.engine.AgentValidationException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlmClientTest {

  private static final Prompt PROMPT = new Prompt("You write specs.", "Add QR codes.", "h");
  private static final String VALID =
      "{\"problemStatement\":\"QR\",\"functional\":[{\"id\":\"FR-1\",\"text\":\"PNG\"}]}";

  /** Answers from a script and keeps the requests. */
  private static final class Scripted implements LlmGateway {
    final Deque<String> replies = new ArrayDeque<>();
    final List<LlmRequest> requests = new ArrayList<>();

    Scripted(String... replies) {
      this.replies.addAll(List.of(replies));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
      requests.add(request);
      return new LlmResponse(replies.removeFirst(), Optional.empty(), 100, 20, "m", Duration.ZERO);
    }
  }

  /** Counts calls and tokens; refuses after {@code max} calls. */
  private static final class Meter implements UsageMeter {
    final int max;
    int calls;
    long tokens;

    Meter(int max) {
      this.max = max;
    }

    @Override
    public void beforeCall() {
      if (calls >= max) {
        throw new BudgetExceededException("LLM call budget used up");
      }
    }

    @Override
    public void record(long inputTokens, long outputTokens) {
      calls++;
      tokens += inputTokens + outputTokens;
    }
  }

  private final JsonSchemas schemas = new JsonSchemas();

  @Test
  void aValidReplyIsParsedAndCharged() {
    Scripted gateway = new Scripted(VALID);
    Meter meter = new Meter(10);

    LlmClient.Structured result =
        new LlmClient(gateway, schemas).completeJson(meter, "TestAgent", PROMPT, "test_spec", 500);

    assertThat(result.json().path("problemStatement").asString()).isEqualTo("QR");
    assertThat(result.response().json()).contains(result.json());
    assertThat(result.promptHash()).isEqualTo("h");
    assertThat(meter.calls).isEqualTo(1);
    assertThat(meter.tokens).isEqualTo(120);
    assertThat(gateway.requests.getFirst().jsonSchema()).contains(schemas.text("test_spec"));
  }

  @Test
  void anInvalidReplyGetsOneRepairAttemptWithTheErrors() {
    Scripted gateway = new Scripted("{\"functional\":[]}", "```json\n" + VALID + "\n```");
    Meter meter = new Meter(10);

    LlmClient.Structured result =
        new LlmClient(gateway, schemas).completeJson(meter, "TestAgent", PROMPT, "test_spec", 500);

    assertThat(result.json().path("functional").get(0).path("id").asString()).isEqualTo("FR-1");
    assertThat(meter.calls).isEqualTo(2);
    LlmRequest repair = gateway.requests.get(1);
    assertThat(repair.systemPrompt()).isEqualTo(PROMPT.system());
    assertThat(repair.userPrompt())
        .startsWith("Add QR codes.")
        .contains("did not match the required JSON Schema")
        .contains("problemStatement")
        .endsWith("Reply again with only the corrected JSON object, nothing else.");
  }

  @Test
  void stillInvalidAfterTheRepairIsAValidationFailure() {
    Scripted gateway = new Scripted("Sure! Here is the spec.", "[1, 2]");

    assertThatThrownBy(() ->
            new LlmClient(gateway, schemas)
                .completeJson(new Meter(10), "TestAgent", PROMPT, "test_spec", 500))
        .isInstanceOf(AgentValidationException.class)
        .hasMessage(
            "TestAgent output does not match the test_spec schema after one repair attempt:"
                + " the reply must be a single JSON object");
    assertThat(gateway.requests.get(1).userPrompt()).contains("the reply is not valid JSON");
  }

  @Test
  void theBudgetIsCheckedBeforeEveryCall() {
    Scripted gateway = new Scripted("{}", VALID);
    Meter meter = new Meter(1);

    assertThatThrownBy(() ->
            new LlmClient(gateway, schemas)
                .completeJson(meter, "TestAgent", PROMPT, "test_spec", 500))
        .isInstanceOf(BudgetExceededException.class); // the repair would exceed it
    assertThat(gateway.requests).hasSize(1);
  }

  @Test
  void plainCompletionsAreChargedToo() {
    Meter meter = new Meter(10);

    LlmResponse response =
        new LlmClient(new Scripted("hello"), schemas)
            .complete(meter, LlmRequest.of("A", "s", "u", 10));

    assertThat(response.text()).isEqualTo("hello");
    assertThat(response.json()).isEmpty();
    assertThat(meter.calls).isEqualTo(1);
    assertThat(LlmClient.unfence("  ```\n{}\n```  ")).isEqualTo("{}");
    assertThat(LlmClient.unfence(" {} ")).isEqualTo("{}");
  }
}
