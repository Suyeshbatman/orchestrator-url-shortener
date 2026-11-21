package com.acme.orchestrator.app.persistence;

import com.acme.orchestrator.core.event.Actor;
import com.acme.orchestrator.core.event.CanonicalJson;
import com.acme.orchestrator.core.event.Event;
import com.acme.orchestrator.core.event.EventDraft;
import com.acme.orchestrator.core.event.EventStore;
import com.acme.orchestrator.core.event.EventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The event log in PostgreSQL. Each append runs in one transaction that takes a per-run advisory
 * lock (so seq and prevHash come from the true head even with concurrent writers), inserts the
 * hash-chained event and rewrites the run's projections. The table rejects UPDATE and DELETE by
 * trigger; this class never issues them.
 */
public final class JdbcEventStore implements EventStore {

  private static final String COLUMNS =
      "seq, run_id, type, stage_id, actor, payload_text, ts, prev_hash, hash";

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final RunProjections projections;

  public JdbcEventStore(
      JdbcClient jdbc, TransactionTemplate tx, Clock clock, RunProjections projections) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.projections = Objects.requireNonNull(projections, "projections");
  }

  @Override
  public Event append(EventDraft draft) {
    return tx.execute(status -> {
      jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:run, 1))")
          .param("run", draft.runId())
          .query((rs, n) -> 1)
          .single();
      Optional<Event> head = head(draft.runId());
      Event event =
          Event.chain(
              head.map(h -> h.seq() + 1).orElse(1L),
              draft.runId(),
              draft.type(),
              draft.stageId(),
              draft.actor(),
              draft.payload(),
              clock.instant(),
              head.map(Event::hash).orElse(Event.GENESIS));
      jdbc.sql(
              "insert into workflow_event ("
                  + COLUMNS
                  + ") "
                  + "values (:seq, :run, :type, :stage, :actor, :payload, :ts, :prev, :hash)")
          .param("seq", event.seq())
          .param("run", event.runId())
          .param("type", event.type().name())
          .param("stage", event.stageId().orElse(null))
          .param("actor", event.actor().toString())
          .param("payload", CanonicalJson.write(event.payload()))
          .param("ts", Json.ts(event.timestamp()))
          .param("prev", event.prevHash())
          .param("hash", event.hash())
          .update();
      projections.refresh(events(draft.runId()));
      return event;
    });
  }

  @Override
  public List<Event> events(String runId) {
    return jdbc.sql("select " + COLUMNS + " from workflow_event where run_id = :run order by seq")
        .param("run", runId)
        .query(JdbcEventStore::event)
        .list();
  }

  @Override
  public Optional<Event> head(String runId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from workflow_event where run_id = :run order by seq desc limit 1")
        .param("run", runId)
        .query(JdbcEventStore::event)
        .optional();
  }

  @Override
  public List<String> runIds() {
    return jdbc.sql("select run_id from workflow_event group by run_id order by min(id)")
        .query(String.class)
        .list();
  }

  @Override
  public Clock clock() {
    return clock;
  }

  private static Event event(ResultSet rs, int row) throws SQLException {
    return new Event(
        rs.getLong("seq"),
        rs.getString("run_id"),
        EventType.valueOf(rs.getString("type")),
        Optional.ofNullable(rs.getString("stage_id")),
        Actor.parse(rs.getString("actor")),
        Json.map(rs.getString("payload_text")),
        Json.instant(rs, "ts"),
        rs.getString("prev_hash"),
        rs.getString("hash"));
  }
}
