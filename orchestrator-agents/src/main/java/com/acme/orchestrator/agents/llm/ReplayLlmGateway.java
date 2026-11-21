package com.acme.orchestrator.agents.llm;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Replay mode (the default): answers from recorded fixtures only, never the network. Deterministic,
 * so demos and tests behave the same everywhere.
 */
public final class ReplayLlmGateway implements LlmGateway {

  private final Path replayDir;

  public ReplayLlmGateway(Path replayDir) {
    this.replayDir = Objects.requireNonNull(replayDir, "replayDir");
  }

  /**
   * @throws ReplayMissingException if no fixture matches, saying how to record one
   */
  @Override
  public LlmResponse complete(LlmRequest request) {
    String hash = request.hash();
    return ReplayFiles.read(replayDir, hash)
        .orElseThrow(() ->
            new ReplayMissingException(
                "no recorded LLM reply for "
                    + request.agent()
                    + " (request "
                    + hash
                    + ") in "
                    + replayDir
                    + ". The prompt, schema or inputs changed since the fixtures were made."
                    + " Re-record with ORCH_LLM_MODE=record and ANTHROPIC_API_KEY set, or add"
                    + " the fixture "
                    + ReplayFiles.file(replayDir, hash).getFileName()
                    + " by hand."));
  }
}
