package com.acme.orchestrator.app.config;

import com.acme.orchestrator.agents.llm.JsonSchemas;
import com.acme.orchestrator.agents.tools.WorkspaceManager;
import com.acme.orchestrator.app.metrics.MetricsRecorder;
import com.acme.orchestrator.app.persistence.ApprovalRepository;
import com.acme.orchestrator.app.persistence.JdbcArtifactStore;
import com.acme.orchestrator.app.persistence.JdbcEventStore;
import com.acme.orchestrator.app.persistence.RunProjections;
import com.acme.orchestrator.app.persistence.RunRepository;
import com.acme.orchestrator.app.run.ActiveRuns;
import com.acme.orchestrator.app.run.OrchestratorProperties;
import com.acme.orchestrator.app.run.RunLauncher;
import com.acme.orchestrator.app.run.RunRecovery;
import com.acme.orchestrator.app.run.RunService;
import com.acme.orchestrator.app.run.StandardRunLauncher;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.gate.GateRegistry;
import com.acme.orchestrator.core.policy.PolicyEngine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestratorProperties.class)
class OrchestratorConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RunProjections runProjections(JdbcClient jdbc) {
    return new RunProjections(jdbc);
  }

  @Bean
  MetricsRecorder metricsRecorder(MeterRegistry registry) {
    return new MetricsRecorder(registry);
  }

  @Bean
  EventStore eventStore(
      JdbcClient jdbc,
      TransactionTemplate tx,
      Clock clock,
      RunProjections projections,
      MetricsRecorder metrics) {
    return new JdbcEventStore(jdbc, tx, clock, projections, List.of(metrics));
  }

  @Bean
  ArtifactStore artifactStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    return new JdbcArtifactStore(jdbc, tx, clock);
  }

  @Bean
  RunRepository runRepository(JdbcClient jdbc, Clock clock) {
    return new RunRepository(jdbc, clock);
  }

  /** Loaded once; each run's RUN_CREATED records the hashes, so edits only affect new runs. */
  @Bean
  PolicyEngine policyEngine(OrchestratorProperties props) {
    return PolicyEngine.load(props.configPath());
  }

  @Bean
  JsonSchemas jsonSchemas() {
    return new JsonSchemas();
  }

  @Bean
  GateRegistry gateRegistry(JsonSchemas schemas) {
    return GateRegistry.standard(schemas);
  }

  @Bean
  WorkspaceManager workspaceManager(OrchestratorProperties props) {
    return new WorkspaceManager(props.sourceUri(), props.workspacePath(), props.baseBranch());
  }

  @Bean(destroyMethod = "close")
  ActiveRuns activeRuns() {
    return new ActiveRuns();
  }

  @Bean
  RunLauncher runLauncher(
      OrchestratorProperties props,
      EventStore events,
      ArtifactStore artifacts,
      PolicyEngine policies,
      GateRegistry gates,
      JsonSchemas schemas,
      WorkspaceManager workspaces,
      Clock clock,
      ActiveRuns active) {
    return new StandardRunLauncher(
        props,
        events,
        artifacts,
        policies,
        gates,
        schemas,
        workspaces,
        System.getenv(),
        clock,
        active);
  }

  @Bean
  ApprovalRepository approvalRepository(JdbcClient jdbc) {
    return new ApprovalRepository(jdbc);
  }

  @Bean
  RunService runService(
      OrchestratorProperties props,
      RunRepository runs,
      ApprovalRepository approvals,
      EventStore events,
      ArtifactStore artifacts,
      RunLauncher launcher,
      ActiveRuns active,
      GateRegistry gates) {
    return new RunService(props, runs, approvals, events, artifacts, launcher, active, gates);
  }

  @Bean
  RunRecovery runRecovery(
      RunRepository runs, EventStore events, RunLauncher launcher, OrchestratorProperties props) {
    return new RunRecovery(runs, events, launcher, props.recoverOnStartup());
  }
}
