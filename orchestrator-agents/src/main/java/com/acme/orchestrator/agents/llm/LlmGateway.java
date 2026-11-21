package com.acme.orchestrator.agents.llm;

/**
 * Port: one model call. Implementations: {@link AnthropicLlmGateway} (live), {@link
 * RecordingLlmGateway} (live, saving each reply as a fixture), {@link ReplayLlmGateway} (fixtures
 * only, no network); {@link RedactingLlmGateway} wraps any of them. Agents use it through {@link
 * LlmClient}, which adds budgets and schema validation.
 *
 * @throws LlmUnavailableException when the model can't be reached (transient: retried later)
 */
@FunctionalInterface
public interface LlmGateway {

  LlmResponse complete(LlmRequest request);
}
