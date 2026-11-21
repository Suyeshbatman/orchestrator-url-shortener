package com.acme.shortener.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a batch in one transaction: a JDBC batch insert into {@code click_event} and an upsert of
 * the per-day totals into {@code click_daily}. Then, outside the transaction, PFADDs the visitor
 * hashes into Redis (best effort: if Redis is down, stats fall back to the DB).
 */
public final class JdbcClickStore implements ClickStore {

  private record DayKey(long linkId, LocalDate day) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final UniqueVisitors uniques;
  private final Counter unknownCodes;

  public JdbcClickStore(
      NamedParameterJdbcTemplate jdbc,
      TransactionTemplate tx,
      UniqueVisitors uniques,
      MeterRegistry meters) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.uniques = uniques;
    this.unknownCodes = Counter.builder("analytics.events.unknown-code").register(meters);
  }

  @Override
  public void write(List<ClickEvent> batch) {
    if (batch.isEmpty()) {
      return;
    }
    Map<String, Long> ids = linkIds(batch);
    List<SqlParameterSource> events = new ArrayList<>(batch.size());
    Map<DayKey, Long> daily = new LinkedHashMap<>();
    Map<DayKey, Set<String>> visitors = new HashMap<>();
    for (ClickEvent e : batch) {
      Long linkId = ids.get(e.code());
      if (linkId == null) {
        unknownCodes.increment(); // redirected, then vanished: not possible today
        continue;
      }
      events.add(
          new MapSqlParameterSource()
              .addValue("linkId", linkId)
              .addValue("at", Timestamp.from(e.occurredAt()))
              .addValue("referrer", e.referrerHost())
              .addValue("ua", e.uaFamily().label())
              .addValue("visitor", e.visitorHash()));
      DayKey day = new DayKey(linkId, LocalDate.ofInstant(e.occurredAt(), ZoneOffset.UTC));
      daily.merge(day, 1L, Long::sum);
      visitors.computeIfAbsent(day, k -> new LinkedHashSet<>()).add(e.visitorHash());
    }
    if (events.isEmpty()) {
      return;
    }

    tx.executeWithoutResult(status -> {
      jdbc.batchUpdate(
          """
          INSERT INTO click_event (link_id, occurred_at, referrer_host, ua_family, visitor_hash)
          VALUES (:linkId, :at, :referrer, :ua, :visitor)
          """,
          events.toArray(SqlParameterSource[]::new));
      jdbc.batchUpdate(
          """
          INSERT INTO click_daily (link_id, day, clicks) VALUES (:linkId, :day, :clicks)
          ON CONFLICT (link_id, day) DO UPDATE SET clicks = click_daily.clicks + EXCLUDED.clicks
          """,
          daily.entrySet().stream()
              .map(d ->
                  new MapSqlParameterSource()
                      .addValue("linkId", d.getKey().linkId())
                      .addValue("day", d.getKey().day())
                      .addValue("clicks", d.getValue()))
              .toArray(SqlParameterSource[]::new));
    });

    visitors.forEach((day, hashes) -> uniques.add(day.linkId(), day.day(), hashes));
  }

  /** One query for the whole batch. */
  private Map<String, Long> linkIds(List<ClickEvent> batch) {
    Set<String> codes = new LinkedHashSet<>();
    batch.forEach(e -> codes.add(e.code()));
    Map<String, Long> ids = new HashMap<>();
    jdbc.query(
        "SELECT code, id FROM link WHERE code IN (:codes)",
        Map.of("codes", codes),
        rs -> {
          ids.put(rs.getString("code"), rs.getLong("id"));
        });
    return ids;
  }
}
