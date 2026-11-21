package com.acme.orchestrator.app.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the approval projection (written by {@link RunProjections}). */
public final class ApprovalRepository {

  /** One human checkpoint. {@code summary} is what the approver is shown. */
  public record ApprovalRow(
      String id,
      String runId,
      String stageId,
      String status,
      String reason,
      Map<String, Object> summary,
      Instant requestedAt,
      Instant decidedAt,
      String decidedBy,
      String comment) {}

  private static final String COLUMNS =
      "id, run_id, stage_id, status, reason, summary::text as summary, requested_at, decided_at,"
          + " decided_by, comment";

  private final JdbcClient jdbc;

  public ApprovalRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ApprovalRow> find(String id) {
    return jdbc.sql("select " + COLUMNS + " from approval where id = :id")
        .param("id", id)
        .query(ApprovalRepository::row)
        .optional();
  }

  /** Newest first; {@code status} empty for all. */
  public List<ApprovalRow> list(Optional<String> status, Optional<String> runId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from approval"
                + " where (cast(:status as text) is null or status = :status)"
                + " and (cast(:run as text) is null or run_id = :run)"
                + " order by requested_at desc, id")
        .param("status", status.orElse(null))
        .param("run", runId.orElse(null))
        .query(ApprovalRepository::row)
        .list();
  }

  private static ApprovalRow row(ResultSet rs, int n) throws SQLException {
    return new ApprovalRow(
        rs.getString("id"),
        rs.getString("run_id"),
        rs.getString("stage_id"),
        rs.getString("status"),
        rs.getString("reason"),
        Json.map(rs.getString("summary")),
        Json.instant(rs, "requested_at"),
        Json.instant(rs, "decided_at"),
        rs.getString("decided_by"),
        rs.getString("comment"));
  }
}
