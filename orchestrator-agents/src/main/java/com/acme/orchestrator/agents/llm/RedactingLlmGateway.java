package com.acme.orchestrator.agents.llm;

import java.util.Objects;

/**
 * Outermost layer: secrets are scrubbed from the prompts before anything else sees them, so they
 * reach neither the model nor a recorded fixture, and the replay hash is of the scrubbed request.
 */
public final class RedactingLlmGateway implements LlmGateway {

  private final LlmGateway delegate;
  private final SecretRedactor redactor;

  public RedactingLlmGateway(LlmGateway delegate, SecretRedactor redactor) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.redactor = Objects.requireNonNull(redactor, "redactor");
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    return delegate.complete(
        request.withPrompts(
            redactor.redact(request.systemPrompt()), redactor.redact(request.userPrompt())));
  }
}
