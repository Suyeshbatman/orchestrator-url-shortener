package com.acme.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.orchestrator.app.TestcontainersConfiguration;
import com.acme.orchestrator.core.artifact.ArtifactDraft;
import com.acme.orchestrator.core.artifact.ArtifactStore;
import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.AuditVerifier;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventDraft;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.Artifact;
import com.acme.orchestrator.core.model.AutonomyLevel;
import com.acme.orchestrator.core.model.Budgets;
import com.acme.orchestrator.core.model.ProducedBy;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.RunMode;
import com.acme.orchestrator.core.model.RunStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "orchestrator.recover-on-startup=false")
class JdbcStoresIT {

  @Autowired EventStore events;
  @Autowired ArtifactStore artifacts;
  @Autowired JdbcClient jdbc;

  private static String newRun() {
    return "run-" + UUID.randomUUID();
  }

  private static Map<String, Object> created(String runId) {
    return RunStateProjector.runCreatedPayload(
        new Run(
            runId,
            "test",
            RunMode.GREENFIELD,
            AutonomyLevel.L1_SUPERVISED,
            RunStatus.CREATED,
            new Budgets(Duration.ofMinutes(10), 10, 1000)));
  }

  // ---- event log ------------------------------------------------------------------------------

  @Test
  void eventsRoundTripExactlySoTheChainStillVerifies() {
    String run = newRun();
    events.append(EventDraft.run(run, EventType.RUN_CREATED, Actor.SYSTEM, created(run)));
    Map<String, Object> tricky = new LinkedHashMap<>();
    tricky.put("zeta", "last key first");
    tricky.put("coverage", new BigDecimal("0.80"));
    tricky.put("big", Long.MAX_VALUE);
    tricky.put("unicode", "ünïcødé ✓ \"quoted\" \\ back\nslash");
    tricky.put(
        "nested", Map.of("list", List.of(1, "two", Map.of("three", true)), "empty", List.of()));
    tricky.put("delay", Duration.ofMillis(1500));
    Event written =
        events.append(
            EventDraft.stage(run, EventType.GATE_EVALUATED, "a", Actor.human("alice"), tricky));

    List<Event> read = events.events(run);

    assertThat(read).hasSize(2);
    assertThat(read.get(1)).isEqualTo(written);
    assertThat(read.get(1).hashMatches()).isTrue();
    assertThat(new AuditVerifier(events).verify(run).valid()).isTrue();
    assertThat(events.head(run)).contains(written);
    assertThat(events.runIds()).contains(run);
    // the jsonb view is queryable
    assertThat(
            jdbc.sql(
                    "select payload->>'coverage' from workflow_event where run_id = :run and seq = 2")
                .param("run", run)
                .query(String.class)
                .single())
        .isEqualTo("0.8");
  }

  @Test
  void concurrentAppendsGetContiguousSeqsAndAValidChain() throws Exception {
    String run = newRun();
    events.append(EventDraft.run(run, EventType.RUN_CREATED, Actor.SYSTEM, created(run)));
    List<Callable<Event>> writers = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      int n = i;
      writers.add(() ->
          events.append(
              EventDraft.stage(
                  run, EventType.GATE_EVALUATED, "s" + (n % 4), Actor.SYSTEM, Map.of("n", n))));
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      for (var f : pool.invokeAll(writers)) {
        f.get();
      }
    }

    List<Event> log = events.events(run);
    assertThat(log)
        .extracting(Event::seq)
        .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 41).boxed().toList());
    assertThat(new AuditVerifier(events).verify(run).valid()).isTrue();
  }

  @Test
  void theLogCannotBeRewrittenEvenWithSql() {
    String run = newRun();
    events.append(EventDraft.run(run, EventType.RUN_CREATED, Actor.SYSTEM, created(run)));

    for (String sql :
        List.of(
            "update workflow_event set actor = 'human:mallory' where run_id = :run",
            "delete from workflow_event where run_id = :run")) {
      assertThatThrownBy(() -> jdbc.sql(sql).param("run", run).update())
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("workflow_event is append-only");
    }
    assertThatThrownBy(() -> jdbc.sql("truncate workflow_event cascade").update())
        .hasStackTraceContaining("workflow_event is append-only: TRUNCATE is not allowed");
    assertThat(events.events(run)).hasSize(1);
  }

  @Test
  void projectionsFollowTheLog() {
    String run = newRun();
    events.append(EventDraft.run(run, EventType.RUN_CREATED, Actor.SYSTEM, created(run)));
    events.append(EventDraft.stage(run, EventType.STAGE_READY, "a", Actor.SYSTEM, Map.of()));
    events.append(EventDraft.stage(run, EventType.STAGE_STARTED, "a", Actor.SYSTEM, Map.of()));
    events.append(
        EventDraft.stage(
            run,
            EventType.APPROVAL_REQUESTED,
            "a",
            Actor.SYSTEM,
            Map.of("reason", "Scope sign-off", "riskScore", 10)));

    assertThat(status(run)).isEqualTo("AWAITING_HUMAN");
    assertThat(
            jdbc.sql(
                    "select status || '/' || attempt from stage_instance where run_id = :run and stage_key = 'a'")
                .param("run", run)
                .query(String.class)
                .single())
        .isEqualTo("WAITING_APPROVAL/1");
    assertThat(approval(run)).isEqualTo("PENDING|Scope sign-off|null|10");

    events.append(
        EventDraft.stage(
            run,
            EventType.APPROVAL_GRANTED,
            "a",
            Actor.human("alice"),
            Map.of("comment", "ok", "role", "APPROVER")));

    assertThat(status(run)).isEqualTo("RUNNING");
    assertThat(approval(run)).isEqualTo("GRANTED|Scope sign-off|human:alice|10");
  }

  @Test
  void aPendingApprovalIsVoidedWhenTheStageIsInvalidated() {
    String run = newRun();
    events.append(EventDraft.run(run, EventType.RUN_CREATED, Actor.SYSTEM, created(run)));
    events.append(EventDraft.stage(run, EventType.STAGE_READY, "a", Actor.SYSTEM, Map.of()));
    events.append(EventDraft.stage(run, EventType.STAGE_STARTED, "a", Actor.SYSTEM, Map.of()));
    events.append(
        EventDraft.stage(
            run, EventType.APPROVAL_REQUESTED, "a", Actor.SYSTEM, Map.of("reason", "r")));
    events.append(
        EventDraft.stage(
            run, EventType.STAGE_INVALIDATED, "a", Actor.SYSTEM, Map.of("reason", "replan")));

    assertThat(approval(run)).startsWith("VOIDED|r|system");
  }

  private String status(String run) {
    return jdbc.sql("select status from run where id = :run")
        .param("run", run)
        .query(String.class)
        .single();
  }

  private String approval(String run) {
    return jdbc.sql(
            "select status || '|' || reason || '|' || coalesce(decided_by, 'null') || '|'"
                + " || coalesce(summary->>'riskScore', '-') from approval where run_id = :run")
        .param("run", run)
        .query(String.class)
        .single();
  }

  // ---- artifacts ------------------------------------------------------------------------------

  private static ArtifactDraft draft(
      String run, String stage, String type, String json, List<Artifact> inputs) {
    return new ArtifactDraft(
        run,
        stage,
        type,
        json,
        ProducedBy.llm("RequirementsAgent", "claude-sonnet-5-5", "a".repeat(64)),
        inputs.stream().map(Artifact::ref).toList(),
        "because");
  }

  @Test
  void artifactsAreVersionedAndKeptByteForByte() {
    String run = newRun();
    String messy = "{ \"b\" : 1,   \"a\":[ 1.50 ] }"; // jsonb would normalise this
    Artifact v1 = artifacts.save(draft(run, "requirements", "requirements_spec", messy, List.of()));
    Artifact v2 =
        artifacts.save(draft(run, "requirements", "requirements_spec", "{\"v\":2}", List.of()));

    assertThat(v1.id()).isEqualTo(run + ":requirements:requirements_spec:v1");
    assertThat(v2.version()).isEqualTo(2);
    assertThat(artifacts.get(v1.id())).contains(v1);
    assertThat(artifacts.get(v1.id()).orElseThrow().contentJson()).isEqualTo(messy);
    assertThat(artifacts.latest(run, "requirements", "requirements_spec")).contains(v2);
    assertThat(artifacts.versions(run, "requirements", "requirements_spec"))
        .containsExactly(v1, v2);
    assertThat(artifacts.get(v1.id()).orElseThrow().producedBy().model())
        .contains("claude-sonnet-5-5");
  }

  @Test
  void latestOfTypeIsTheLastSavedAcrossStagesAndLineageIsFollowed() {
    String run = newRun();
    Artifact requirement =
        artifacts.save(draft(run, "input", "requirement", "{\"text\":\"QR\"}", List.of()));
    Artifact spec = artifacts.save(draft(run, "requirements", "spec", "{}", List.of(requirement)));
    Artifact plan =
        artifacts.save(draft(run, "decomposition", "plan", "{}", List.of(spec, requirement)));
    Artifact docsDraft =
        artifacts.save(draft(run, "docs_draft", "docs", "{\"d\":1}", List.of(spec)));
    Artifact docsFinal =
        artifacts.save(draft(run, "docs_finalize", "docs", "{\"d\":2}", List.of(docsDraft)));

    assertThat(artifacts.latestOfType(run, "docs")).contains(docsFinal);
    assertThat(artifacts.byRun(run)).containsExactly(requirement, spec, plan, docsDraft, docsFinal);
    assertThat(artifacts.lineage(plan.id()).ancestors())
        .extracting(Artifact::id)
        .containsExactlyInAnyOrder(spec.id(), requirement.id());
    assertThat(artifacts.lineage(docsFinal.id()).ancestors())
        .extracting(Artifact::id)
        .containsExactlyInAnyOrder(docsDraft.id(), spec.id(), requirement.id());
  }

  @Test
  void inputsMustMatchStoredArtifacts() {
    String run = newRun();
    Artifact real = artifacts.save(draft(run, "a", "x", "{}", List.of()));
    ArtifactDraft forged =
        new ArtifactDraft(
            run,
            "b",
            "y",
            "{}",
            ProducedBy.deterministic("t"),
            List.of(
                new com.acme.orchestrator.core.model.ArtifactRef(
                    real.id(), "x", 1, "f".repeat(64))),
            "");

    assertThatThrownBy(() -> artifacts.save(forged))
        .hasMessageContaining("does not match the stored artifact");
    assertThatThrownBy(() -> artifacts.lineage("nope")).hasMessage("unknown artifact nope");
    assertThat(artifacts.byRun(run)).containsExactly(real);
  }
}
