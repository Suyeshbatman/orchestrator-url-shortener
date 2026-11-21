package com.acme.orchestrator.agents.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * What the model said.
 *
 * @param json the parsed reply, for structured requests (set by {@link LlmClient})
 */
public record LlmResponse(
    String text,
    Optional<JsonNode> json,
    long inputTokens,
    long outputTokens,
    String model,
    Duration latency) {

  public LlmResponse {
    Objects.requireNonNull(text, "text");
    json = Objects.requireNonNull(json, "json");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(latency, "latency");
  }

  public LlmResponse withJson(JsonNode parsed) {
    return new LlmResponse(text, Optional.of(parsed), inputTokens, outputTokens, model, latency);
  }
}
