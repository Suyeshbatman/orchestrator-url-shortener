package com.acme.orchestrator.agents.llm;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Record mode: calls the real model and saves each reply as a fixture under {@code
 * scenarios/<id>/replay/}, keyed by {@link LlmRequest#hash()}, so the run can later be replayed
 * without an API key.
 */
public final class RecordingLlmGateway implements LlmGateway {

  private final LlmGateway delegate;
  private final Path replayDir;

  public RecordingLlmGateway(LlmGateway delegate, Path replayDir) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.replayDir = Objects.requireNonNull(replayDir, "replayDir");
  }

  @Override
  public LlmResponse complete(LlmRequest request) {
    LlmResponse response = delegate.complete(request);
    ReplayFiles.write(replayDir, request, response);
    return response;
  }
}
