package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.agents.llm.PromptTemplates.Prompt;
import com.acme.orchestrator.core.engine.AgentValidationException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What agents call: charges every call to the run's budget and, for structured output, validates
 * the reply against the artifact type's JSON Schema. An invalid reply gets one repair attempt (the
 * same request with the validation errors appended); if that is still invalid the attempt fails
 * with an {@link AgentValidationException} (retried by the engine with the errors as feedback).
 */
public final class LlmClient {

  /** A structured reply: the validated JSON, the raw response, and the prompt hash for lineage. */
  public record Structured(JsonNode json, LlmResponse response, String promptHash) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern FENCE =
      Pattern.compile("(?s)\\A\\s*```(?:json)?\\s*(.*?)\\s*```\\s*\\z");
  private static final int MAX_LISTED_ERRORS = 20;

  private final LlmGateway gateway;
  private final JsonSchemas schemas;

  public LlmClient(LlmGateway gateway, JsonSchemas schemas) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.schemas = Objects.requireNonNull(schemas, "schemas");
  }

  /** One call, charged to {@code meter}. */
  public LlmResponse complete(UsageMeter meter, LlmRequest request) {
    meter.beforeCall();
    LlmResponse response = gateway.complete(request);
    meter.record(response.inputTokens(), response.outputTokens());
    return response;
  }

  /**
   * A reply that must be a JSON object matching the schema for {@code artifactType}.
   *
   * @throws AgentValidationException if it still doesn't after one repair attempt
   */
  public Structured completeJson(
      UsageMeter meter, String agent, Prompt prompt, String artifactType, int maxTokens) {
    LlmRequest request =
        LlmRequest.of(agent, prompt.system(), prompt.user(), maxTokens)
            .withSchema(schemas.text(artifactType));
    LlmResponse first = complete(meter, request);
    List<String> problems = problems(first.text(), artifactType);
    if (problems.isEmpty()) {
      return new Structured(
          parse(first.text()), first.withJson(parse(first.text())), prompt.hash());
    }

    LlmRequest repair =
        request.withPrompts(
            request.systemPrompt(),
            request.userPrompt()
                + "\n\n---\nYour previous reply did not match the required JSON Schema:\n- "
                + String.join("\n- ", limit(problems))
                + "\n\nReply again with only the corrected JSON object, nothing else.");
    LlmResponse second = complete(meter, repair);
    List<String> still = problems(second.text(), artifactType);
    if (!still.isEmpty()) {
      throw new AgentValidationException(
          agent
              + " output does not match the "
              + artifactType
              + " schema after one repair attempt: "
              + String.join("; ", limit(still)));
    }
    JsonNode json = parse(second.text());
    return new Structured(json, second.withJson(json), prompt.hash());
  }

  private List<String> problems(String text, String artifactType) {
    String json = unfence(text);
    try {
      JsonNode node = JSON.readTree(json);
      if (node == null || !node.isObject()) {
        return List.of("the reply must be a single JSON object");
      }
    } catch (JacksonException e) {
      return List.of("the reply is not valid JSON: " + e.getOriginalMessage());
    }
    return schemas.validate(artifactType, json);
  }

  private static JsonNode parse(String text) {
    return JSON.readTree(unfence(text));
  }

  /** Models sometimes wrap JSON in a Markdown code fence despite instructions. */
  static String unfence(String text) {
    Matcher m = FENCE.matcher(text);
    return m.matches() ? m.group(1) : text.strip();
  }

  private static List<String> limit(List<String> problems) {
    return problems.size() <= MAX_LISTED_ERRORS ? problems : problems.subList(0, MAX_LISTED_ERRORS);
  }
}
