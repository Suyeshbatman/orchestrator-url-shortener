package com.acme.orchestrator.app.persistence;

import static com.acme.orchestrator.core.event.EventType.APPROVAL_GRANTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REJECTED;
import static com.acme.orchestrator.core.event.EventType.APPROVAL_REQUESTED;

import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventType;
import com.acme.orchestrator.core.event.RunState;
import com.acme.orchestrator.core.event.RunStateProjector;
import com.acme.orchestrator.core.model.Run;
import com.acme.orchestrator.core.model.StageInstance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rewrites the run, stage_instance and approval rows of one run from its whole log (in the append's
 * transaction). Re-projecting from scratch keeps the tables exactly what the projector says, at the
 * cost of reading the run's log on every append (hundreds of events at most).
 */
public final class RunProjections {

  /** Events that end a pending approval without a decision. */
  private static final Set<EventType> VOIDING =
      Set.of(EventType.STAGE_INVALIDATED, EventType.ROLLED_BACK);

  private static final Set<EventType> RUN_ENDS =
      Set.of(
          EventType.RUN_HALTED,
          EventType.RUN_COMPLETED,
          EventType.RUN_FAILED,
          EventType.ROLLED_BACK);

  private final JdbcClient jdbc;

  public RunProjections(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void refresh(List<Event> log) {
    RunState state = RunStateProjector.project(log);
    Run run = state.run();
    jdbc.sql(
            """
            insert into run (id, workflow_id, mode, autonomy_level, status, created_at, updated_at)
            values (:id, :workflow, :mode, :level, :status, :created, :updated)
            on conflict (id) do update set
              workflow_id = excluded.workflow_id, mode = excluded.mode,
              autonomy_level = excluded.autonomy_level, status = excluded.status,
              updated_at = excluded.updated_at
            """)
        .param("id", run.id())
        .param("workflow", run.workflowId())
        .param("mode", run.mode().name())
        .param("level", run.autonomyLevel().name())
        .param("status", run.status().name())
        .param("created", Json.ts(log.getFirst().timestamp()))
        .param("updated", Json.ts(log.getLast().timestamp()))
        .update();

    for (StageInstance s : state.stages().values()) {
      jdbc.sql(
              """
              insert into stage_instance
                (run_id, stage_key, stage_id, task_id, status, attempt, loop_count, started_at, ended_at)
              values (:run, :key, :stage, :task, :status, :attempt, :loops, :started, :ended)
              on conflict (run_id, stage_key) do update set
                status = excluded.status, attempt = excluded.attempt,
                loop_count = excluded.loop_count, started_at = excluded.started_at,
                ended_at = excluded.ended_at
              """)
          .param("run", run.id())
          .param("key", s.key())
          .param("stage", s.stageId())
          .param("task", s.taskId().orElse(null))
          .param("status", s.status().name())
          .param("attempt", s.attempt())
          .param("loops", s.loopCount())
          .param("started", Json.ts(s.startedAt().orElse(null)))
          .param("ended", Json.ts(s.endedAt().orElse(null)))
          .update();
    }

    for (Approval a : approvals(log)) {
      jdbc.sql(
              """
              insert into approval
                (id, run_id, stage_id, status, reason, summary, requested_at, decided_at, decided_by, comment)
              values (:id, :run, :stage, :status, :reason, cast(:summary as jsonb), :requested,
                      :decided, :by, :comment)
              on conflict (id) do update set
                status = excluded.status, decided_at = excluded.decided_at,
                decided_by = excluded.decided_by, comment = excluded.comment
              """)
          .param("id", a.id)
          .param("run", run.id())
          .param("stage", a.stageId)
          .param("status", a.status)
          .param("reason", a.reason)
          .param("summary", a.summary)
          .param("requested", Json.ts(a.requestedAt))
          .param("decided", Json.ts(a.decidedAt))
          .param("by", a.decidedBy)
          .param("comment", a.comment)
          .update();
    }
  }

  /** One row per APPROVAL_REQUESTED, with what became of it. */
  static List<Approval> approvals(List<Event> log) {
    List<Approval> all = new ArrayList<>();
    Map<String, Approval> pending = new LinkedHashMap<>(); // stage → open request
    for (Event e : log) {
      String stage = e.stageId().orElse(null);
      if (e.type() == APPROVAL_REQUESTED) {
        Approval a = new Approval();
        a.id = e.runId() + ":" + e.seq();
        a.stageId = stage;
        a.status = "PENDING";
        a.reason = e.payloadString("reason").orElse("");
        a.summary = CanonicalJson.write(e.payload());
        a.requestedAt = e.timestamp();
        all.add(a);
        pending.put(stage, a);
      } else if ((e.type() == APPROVAL_GRANTED || e.type() == APPROVAL_REJECTED)
          && pending.containsKey(stage)) {
        Approval a = pending.remove(stage);
        a.status = e.type() == APPROVAL_GRANTED ? "GRANTED" : "REJECTED";
        a.decidedAt = e.timestamp();
        a.decidedBy = e.actor().toString();
        a.comment = e.payloadString("comment").orElse("");
      } else if (stage != null && VOIDING.contains(e.type()) && pending.containsKey(stage)) {
        void_(pending.remove(stage), e);
      } else if (stage == null && RUN_ENDS.contains(e.type())) {
        pending.values().forEach(a -> void_(a, e));
        pending.clear();
      }
    }
    return all;
  }

  private static void void_(Approval a, Event e) {
    a.status = "VOIDED";
    a.decidedAt = e.timestamp();
    a.decidedBy = e.actor().toString();
    a.comment = e.type().name().toLowerCase(java.util.Locale.ROOT);
  }

  static final class Approval {
    String id;
    String stageId;
    String status;
    String reason;
    String summary;
    Instant requestedAt;
    Instant decidedAt;
    String decidedBy;
    String comment;
  }
}
