package com.acme.orchestrator.agents.llm;

import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.model.Hashing;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One LLM call.
 *
 * @param jsonSchema the JSON Schema the reply must match (its text), if the reply is structured
 * @param temperature sent only when set (the 3.x SDK has no typed setter for it)
 * @param task the task of a fanned-out stage the call is for; not sent and not part of the replay
 *     key (the prompt already says which task), it names hand-written replies per task
 */
public record LlmRequest(
    String agent,
    String systemPrompt,
    String userPrompt,
    Optional<String> jsonSchema,
    int maxTokens,
    OptionalDouble temperature,
    Optional<String> task) {

  public LlmRequest {
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(systemPrompt, "systemPrompt");
    Objects.requireNonNull(userPrompt, "userPrompt");
    jsonSchema = Objects.requireNonNull(jsonSchema, "jsonSchema");
    temperature = Objects.requireNonNull(temperature, "temperature");
    task = Objects.requireNonNull(task, "task");
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
  }

  public static LlmRequest of(String agent, String system, String user, int maxTokens) {
    return new LlmRequest(
        agent, system, user, Optional.empty(), maxTokens, OptionalDouble.empty(), Optional.empty());
  }

  public LlmRequest withSchema(String schema) {
    return new LlmRequest(
        agent, systemPrompt, userPrompt, Optional.of(schema), maxTokens, temperature, task);
  }

  public LlmRequest withPrompts(String system, String user) {
    return new LlmRequest(agent, system, user, jsonSchema, maxTokens, temperature, task);
  }

  public LlmRequest withTask(Optional<String> taskId) {
    return new LlmRequest(
        agent, systemPrompt, userPrompt, jsonSchema, maxTokens, temperature, taskId);
  }

  /**
   * The replay key: SHA-256 of the canonical JSON of agent, prompts and schema. Token limits and
   * sampling are left out, so tuning them doesn't invalidate recorded fixtures.
   */
  public String hash() {
    Map<String, Object> key = new LinkedHashMap<>();
    key.put("agent", agent);
    key.put("system", systemPrompt);
    key.put("user", userPrompt);
    jsonSchema.ifPresent(s -> key.put("schema", s));
    return Hashing.sha256Hex(CanonicalJson.write(key));
  }
}
