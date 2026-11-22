package com.acme.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Scenario 7.1 end to end with every validation build in the Docker sandbox: a private Docker
 * daemon for the shortener's Testcontainers tests and a container that sees only the workspace (see
 * {@code DockerSandbox}). The same fixtures must still match, so the sandboxed build must produce
 * the same results as one on this machine. Runs only with {@code -Pe2e}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "orchestrator.recover-on-startup=false",
      "orchestrator.validation-sandbox=docker"
    })
@AutoConfigureMockMvc
@AutoConfigureMetrics
class SandboxedScenarioE2E extends ScenarioE2ESupport {

  @Test
  void theGreenfieldScenarioCompletesWithItsBuildInTheSandbox() throws Exception {
    runEndToEnd("greenfield-qr-code");

    assertThat(lastValidationSummary).startsWith("build SUCCESS (sandboxed),");
  }
}
