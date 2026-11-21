package com.acme.orchestrator.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.agents.llm.LlmGateways.Config;
import com.acme.orchestrator.agents.llm.LlmGateways.Mode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmGatewaysTest {

  private static final Path DIR = Path.of("scenarios", "qr", "replay");
  private static final SecretRedactor NO_SECRETS = new SecretRedactor(List.of());

  @Test
  void replayIsTheDefaultAndNeedsNoKey() {
    Config config = Config.fromEnvironment(Map.of(), DIR);

    assertThat(config.mode()).isEqualTo(Mode.REPLAY);
    assertThat(config.model()).isEqualTo(Config.DEFAULT_MODEL);
    assertThat(config.apiKey()).isEmpty();
    assertThat(config.fallbackModel()).isEmpty();
    assertThat(LlmGateways.create(config, NO_SECRETS)).isInstanceOf(RedactingLlmGateway.class);
  }

  @Test
  void theEnvironmentPicksModeModelAndFallback() {
    Config config =
        Config.fromEnvironment(
            Map.of(
                "ORCH_LLM_MODE", " Record ",
                "ANTHROPIC_API_KEY", "sk-ant-test",
                "ORCH_LLM_MODEL", "claude-opus-5-5",
                "ORCH_LLM_FALLBACK_MODEL", "claude-sonnet-5-5"),
            DIR);

    assertThat(config.mode()).isEqualTo(Mode.RECORD);
    assertThat(config.model()).isEqualTo("claude-opus-5-5");
    assertThat(config.fallbackModel()).contains("claude-sonnet-5-5");
    assertThat(LlmGateways.create(config, NO_SECRETS)).isNotNull();
  }

  @Test
  void liveAndRecordNeedAKey() {
    for (String mode : List.of("live", "record")) {
      Config config =
          Config.fromEnvironment(Map.of("ORCH_LLM_MODE", mode, "ANTHROPIC_API_KEY", " "), DIR);
      assertThatThrownBy(() -> LlmGateways.create(config, NO_SECRETS))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("ORCH_LLM_MODE=" + mode + " needs ANTHROPIC_API_KEY (or use replay mode)");
    }
  }

  @Test
  void anUnknownModeIsRejected() {
    assertThatThrownBy(() -> Config.fromEnvironment(Map.of("ORCH_LLM_MODE", "yolo"), DIR))
        .hasMessage("ORCH_LLM_MODE must be live, record or replay, was 'yolo'");
  }
}
