package com.acme.orchestrator.agents.llm;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the gateway for a run from configuration ({@code ORCH_LLM_MODE}, {@code
 * ANTHROPIC_API_KEY}, {@code ORCH_LLM_MODEL}, {@code ORCH_LLM_FALLBACK_MODEL}). Every mode is
 * wrapped in the {@link RedactingLlmGateway}.
 */
public final class LlmGateways {

  public enum Mode {
    LIVE,
    RECORD,
    REPLAY
  }

  /**
   * @param replayDir {@code scenarios/<id>/replay}: read in replay mode, written in record mode
   */
  public record Config(
      Mode mode,
      Optional<String> apiKey,
      String model,
      Optional<String> fallbackModel,
      Path replayDir,
      Optional<String> baseUrl) {

    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    public Config {
      Objects.requireNonNull(mode, "mode");
      apiKey = Objects.requireNonNull(apiKey, "apiKey").filter(k -> !k.isBlank());
      Objects.requireNonNull(model, "model");
      fallbackModel =
          Objects.requireNonNull(fallbackModel, "fallbackModel").filter(m -> !m.isBlank());
      Objects.requireNonNull(replayDir, "replayDir");
      baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    }

    /** Replay is the default: it needs no API key. */
    public static Config fromEnvironment(Map<String, String> env, Path replayDir) {
      String mode = env.getOrDefault("ORCH_LLM_MODE", "replay").strip().toUpperCase(Locale.ROOT);
      Mode parsed;
      try {
        parsed = Mode.valueOf(mode.isEmpty() ? "REPLAY" : mode);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "ORCH_LLM_MODE must be live, record or replay, was '" + env.get("ORCH_LLM_MODE") + "'");
      }
      String model = env.getOrDefault("ORCH_LLM_MODEL", "");
      return new Config(
          parsed,
          Optional.ofNullable(env.get("ANTHROPIC_API_KEY")),
          model.isBlank() ? DEFAULT_MODEL : model.strip(),
          Optional.ofNullable(env.get("ORCH_LLM_FALLBACK_MODEL")),
          replayDir,
          Optional.empty());
    }
  }

  private LlmGateways() {}

  /**
   * @throws IllegalStateException for live or record mode without an API key
   */
  public static LlmGateway create(Config config, SecretRedactor redactor) {
    LlmGateway gateway =
        switch (config.mode()) {
          case REPLAY -> new ReplayLlmGateway(config.replayDir());
          case LIVE -> live(config);
          case RECORD -> new RecordingLlmGateway(live(config), config.replayDir());
        };
    return new RedactingLlmGateway(gateway, redactor);
  }

  private static LlmGateway live(Config config) {
    String key =
        config
            .apiKey()
            .orElseThrow(() ->
                new IllegalStateException(
                    "ORCH_LLM_MODE="
                        + config.mode().name().toLowerCase(Locale.ROOT)
                        + " needs ANTHROPIC_API_KEY (or use replay mode)"));
    return new AnthropicLlmGateway(
        key,
        config.baseUrl(),
        AnthropicLlmGateway.Settings.of(config.model(), config.fallbackModel()));
  }
}
