package com.acme.orchestrator.core.engine;

import java.util.Objects;
import java.util.Optional;

/**
 * One artifact an agent produced. The coordinator stores it (version, hash, lineage); the agent
 * never writes to the store itself.
 *
 * @param model LLM model id, if one was used
 * @param promptHash SHA-256 of the exact prompt, if an LLM was used
 */
public record ArtifactOutput(
    String type,
    String contentJson,
    String rationale,
    Optional<String> model,
    Optional<String> promptHash) {

  public ArtifactOutput {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentJson, "contentJson");
    rationale = rationale == null ? "" : rationale;
    model = Objects.requireNonNull(model, "model");
    promptHash = Objects.requireNonNull(promptHash, "promptHash");
  }

  /** From a deterministic agent (no LLM). */
  public static ArtifactOutput of(String type, String contentJson, String rationale) {
    return new ArtifactOutput(type, contentJson, rationale, Optional.empty(), Optional.empty());
  }
}
