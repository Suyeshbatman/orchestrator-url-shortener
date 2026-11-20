package com.acme.shortener.idempotency;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code idempotency_record}. {@code key} holds sha256(apiKeyName:Idempotency-Key).
 */
@Repository
public class IdempotencyRepository {

  /** A stored record; {@code responseStatus} is null only while the claiming transaction runs. */
  public record Record(String requestHash, Integer responseStatus, String responseBody) {}

  private final JdbcClient jdbc;

  public IdempotencyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the claim. Returns false if the key exists. If another open transaction holds the same
   * key, Postgres blocks here until it commits or rolls back.
   */
  public boolean tryClaim(String storageKey, String apiKeyName, String requestHash, Instant now) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_record (key, api_key_name, request_hash, created_at)
                VALUES (:key, :name, :hash, :now)
                ON CONFLICT (key) DO NOTHING
                """)
            .param("key", storageKey)
            .param("name", apiKeyName)
            .param("hash", requestHash)
            .param("now", Timestamp.from(now))
            .update();
    return inserted == 1;
  }

  public Optional<Record> find(String storageKey) {
    return jdbc.sql(
            """
            SELECT request_hash, response_status, response_body::text AS response_body
            FROM idempotency_record WHERE key = :key
            """)
        .param("key", storageKey)
        .query((rs, n) ->
            new Record(
                rs.getString("request_hash"),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body")))
        .optional();
  }

  public void complete(String storageKey, int status, String bodyJson) {
    jdbc.sql(
            """
            UPDATE idempotency_record
            SET response_status = :status, response_body = CAST(:body AS jsonb)
            WHERE key = :key
            """)
        .param("status", status)
        .param("body", bodyJson)
        .param("key", storageKey)
        .update();
  }

  public int deleteIfOlderThan(String storageKey, Instant cutoff) {
    return jdbc.sql("DELETE FROM idempotency_record WHERE key = :key AND created_at < :cutoff")
        .param("key", storageKey)
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }

  public int deleteOlderThan(Instant cutoff) {
    return jdbc.sql("DELETE FROM idempotency_record WHERE created_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
