package com.acme.orchestrator.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Provenance of an artifact.
 *
 * @param model LLM model id; empty for deterministic agents (e.g. validation)
 * @param promptHash SHA-256 of the exact prompt sent; empty when no LLM was used
 */
public record ProducedBy(String agent, Optional<String> model, Optional<String> promptHash) {

  public ProducedBy {
    Objects.requireNonNull(agent, "agent");
    model = Objects.requireNonNull(model, "model");
    promptHash = Objects.requireNonNull(promptHash, "promptHash");
  }

  public static ProducedBy deterministic(String agent) {
    return new ProducedBy(agent, Optional.empty(), Optional.empty());
  }

  public static ProducedBy llm(String agent, String model, String promptHash) {
    return new ProducedBy(agent, Optional.of(model), Optional.of(promptHash));
  }
}
