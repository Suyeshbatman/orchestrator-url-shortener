package com.acme.orchestrator.app.persistence;

import com.acme.orchestrator.core.model.Run;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The run table: how a run was launched (workflow file, scenario) and its projected status. */
public final class RunRepository {

  /** A run row. */
  public record RunRow(
      String id,
      String workflowId,
      String workflowFile,
      Optional<String> scenarioId,
      String mode,
      String autonomyLevel,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  private static final String COLUMNS =
      "id, workflow_id, workflow_file, scenario_id, mode, autonomy_level, status, created_at,"
          + " updated_at";

  private final JdbcClient jdbc;
  private final Clock clock;

  public RunRepository(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Records how a run is launched, before its first event (the projection fills in the rest). */
  public void registerLaunch(Run run, String workflowFile, Optional<String> scenarioId) {
    Instant now = clock.instant();
    jdbc.sql(
            """
            insert into run (id, workflow_id, workflow_file, scenario_id, mode, autonomy_level,
                             status, created_at, updated_at)
            values (:id, :workflow, :file, :scenario, :mode, :level, :status, :now, :now)
            on conflict (id) do update set workflow_file = excluded.workflow_file,
                                           scenario_id = excluded.scenario_id
            """)
        .param("id", run.id())
        .param("workflow", run.workflowId())
        .param("file", workflowFile)
        .param("scenario", scenarioId.orElse(null))
        .param("mode", run.mode().name())
        .param("level", run.autonomyLevel().name())
        .param("status", run.status().name())
        .param("now", Json.ts(now))
        .update();
  }

  public Optional<RunRow> find(String id) {
    return jdbc.sql("select " + COLUMNS + " from run where id = :id")
        .param("id", id)
        .query(RunRepository::row)
        .optional();
  }

  public List<RunRow> all() {
    return jdbc.sql("select " + COLUMNS + " from run order by created_at desc, id")
        .query(RunRepository::row)
        .list();
  }

  /** Runs that have not finished: they need a coordinator (after a restart, a new one). */
  public List<RunRow> unfinished() {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from run where status not in ('COMPLETED', 'FAILED', 'ROLLED_BACK')"
                + " order by created_at, id")
        .query(RunRepository::row)
        .list();
  }

  private static RunRow row(ResultSet rs, int n) throws SQLException {
    return new RunRow(
        rs.getString("id"),
        rs.getString("workflow_id"),
        rs.getString("workflow_file"),
        Optional.ofNullable(rs.getString("scenario_id")),
        rs.getString("mode"),
        rs.getString("autonomy_level"),
        rs.getString("status"),
        Json.instant(rs, "created_at"),
        Json.instant(rs, "updated_at"));
  }
}
