package com.acme.orchestrator.app.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.orchestrator.app.TestcontainersConfiguration;
import com.acme.orchestrator.app.persistence.RunRepository;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.engine.Agent;
import com.acme.orchestrator.core.engine.AgentRegistry;
import com.acme.orchestrator.core.engine.AgentResult;
import com.acme.orchestrator.core.engine.ArtifactOutput;
import com.acme.orchestrator.core.engine.Command;
import com.acme.orchestrator.core.engine.Coordinator;
import com.acme.orchestrator.core.engine.GateEvaluator;
import com.acme.orchestrator.core.engine.ToolRegistry;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import com.acme.orchestrator.core.model.WorkflowDefinition;
import com.acme.orchestrator.core.model.WorkflowLoader;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The engine on PostgreSQL, and a restart that picks up an interrupted run. */
@Import({TestcontainersConfiguration.class, RunRecoveryIT.FakeAgents.class})
@SpringBootTest(properties = "orchestrator.recover-on-startup=false")
class RunRecoveryIT {

  static final Duration WAIT = Duration.ofSeconds(20);
  static final WorkflowDefinition WORKFLOW =
      new WorkflowLoader()
          .parse(
              """
              workflow: test
              budgets: { maxWallClock: 10m, maxLlmCalls: 10, maxTokens: 1000 }
              stages:
                - id: a
                  agent: A
                  retry: { maxAttempts: 2, baseDelay: 10ms, maxDelay: 10ms, jitter: false }
                - id: b
                  agent: A
                  dependsOn: [a]
                  approval: { required: true, reason: "sign-off" }
              """,
              "test.yml");

  /** What stage a does; replaced per test. */
  static volatile Agent stageA;

  static final Agent OK =
      ctx ->
          AgentResult.success(
              "ok", ArtifactOutput.of(ctx.stageKey(), "{\"by\":\"" + ctx.stageKey() + "\"}", "t"));

  /** Launches runs of {@link #WORKFLOW} with fake agents on the real (JDBC) stores. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FakeAgents {
    @Bean
    @Primary
    RunLauncher fakeLauncher(EventStore events, ArtifactStore artifacts, ActiveRuns active) {
      return (row, run) -> {
        Agent dispatching = ctx -> (ctx.stageKey().equals("a") ? stageA : OK).execute(ctx);
        Coordinator coordinator =
            Coordinator.start(
                WORKFLOW,
                run,
                new Coordinator.Services(
                    events,
                    artifacts,
                    AgentRegistry.of(Map.of("A", dispatching)),
                    GateEvaluator.PASS_ALL,
                    ToolRegistry.EMPTY));
        active.put(coordinator);
        return coordinator;
      };
    }
  }

  @Autowired RunLauncher launcher;
  @Autowired RunRecovery recovery;
  @Autowired RunRepository runs;
  @Autowired EventStore events;
  @Autowired ActiveRuns active;
  @Autowired JdbcClient jdbc;

  private String runId;
  private Run run;

  @BeforeEach
  void newRun() {
    runId = "run-" + UUID.randomUUID();
    run = Run.create(runId, WORKFLOW, RunMode.GREENFIELD, AutonomyLevel.L1_SUPERVISED);
    runs.registerLaunch(run, "test.yml", Optional.of("demo"));
    stageA = OK;
  }

  private Coordinator launch() {
    return launcher.launch(runs.find(runId).orElseThrow(), run);
  }

  private String column(String sql) {
    return jdbc.sql(sql).param("run", runId).query(String.class).single();
  }

  @Test
  void aRunExecutesOnPostgresAndItsTablesFollowTheLog() throws Exception {
    Coordinator coordinator = launch();
    coordinator.await(s -> s.run().status() == RunStatus.AWAITING_HUMAN, WAIT);

    assertThat(column("select status from run where id = :run")).isEqualTo("AWAITING_HUMAN");
    assertThat(column("select status from approval where run_id = :run")).isEqualTo("PENDING");
    assertThat(column("select scenario_id || '/' || workflow_file from run where id = :run"))
        .isEqualTo("demo/test.yml");

    coordinator.submit(
        new Command.ApprovalDecision(
            "b",
            Actor.human("alice"),
            Command.ApprovalDecision.APPROVER,
            Command.ApprovalDecision.Decision.APPROVE,
            "ok"));
    coordinator.await(s -> s.run().status() == RunStatus.COMPLETED, WAIT);

    assertThat(column("select status from run where id = :run")).isEqualTo("COMPLETED");
    assertThat(
            column(
                "select string_agg(stage_key || '=' || status, ',' order by stage_key) from stage_instance where run_id = :run"))
        .isEqualTo("a=SUCCEEDED,b=SUCCEEDED");
    assertThat(column("select status || ' by ' || decided_by from approval where run_id = :run"))
        .isEqualTo("GRANTED by human:alice");
    assertThat(column("select count(*)::text from artifact where run_id = :run")).isEqualTo("2");
    assertThat(new AuditVerifier(events).verify(runId).valid()).isTrue();
    assertThat(runs.unfinished()).extracting(RunRepository.RunRow::id).doesNotContain(runId);
  }

  @Test
  void afterARestartAnInterruptedRunIsResumedAndTheLostAttemptRetried() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    stageA =
        ctx -> {
          if (calls.incrementAndGet() == 1) {
            started.countDown();
            new CountDownLatch(1).await(); // until the process "dies"
          }
          return OK.execute(ctx);
        };
    Coordinator first = launch();
    started.await(10, TimeUnit.SECONDS);
    first.close(); // the process stops mid-attempt; the log says stage a is RUNNING
    assertThat(column("select status from stage_instance where run_id = :run and stage_key = 'a'"))
        .isEqualTo("RUNNING");

    assertThat(recovery.recoverAll()).contains(runId);

    Coordinator resumed = active.get(runId).orElseThrow();
    assertThat(resumed).isNotSameAs(first);
    resumed.await(s -> s.run().status() == RunStatus.AWAITING_HUMAN, WAIT);
    Event retried =
        events.events(runId).stream()
            .filter(e -> e.type() == EventType.STAGE_RETRIED)
            .findFirst()
            .orElseThrow();
    assertThat(retried.payload())
        .containsEntry(
            "reason", "interrupted: the coordinator stopped while this attempt was running")
        .containsEntry("kind", "TRANSIENT");
    assertThat(calls).hasValue(2);
    assertThat(new AuditVerifier(events).verify(runId).valid()).isTrue();
  }

  @Test
  void finishedRunsAreNotResumed() throws Exception {
    stageA =
        ctx -> AgentResult.failure("boom", com.acme.orchestrator.core.engine.FailureKind.FATAL);
    launch().await(s -> s.run().status() == RunStatus.FAILED, WAIT);

    assertThat(recovery.recoverAll()).doesNotContain(runId);
  }
}
