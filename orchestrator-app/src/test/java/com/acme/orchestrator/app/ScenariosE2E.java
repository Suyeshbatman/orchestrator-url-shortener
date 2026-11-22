package com.acme.orchestrator.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * The three Phase 7 scenarios end to end through the API on PostgreSQL (see {@link
 * ScenarioE2ESupport}); validation builds run on this machine (replayed fixtures are reviewed
 * code). Runs only with {@code -Pe2e}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "orchestrator.recover-on-startup=false")
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ScenariosE2E extends ScenarioE2ESupport {

  @ParameterizedTest
  @ValueSource(strings = {"greenfield-qr-code", "brownfield-expiry-bf1", "ambiguous-abuse"})
  void theScenarioRunsEndToEnd(String id) throws Exception {
    JsonNode metrics = runEndToEnd(id);
    // the numbers docs/final-summary.md quotes: the summary after each scenario, the last over all
    Path report = Path.of("target", "e2e", "metrics-summary.json");
    Files.createDirectories(report.getParent());
    Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));
  }
}
