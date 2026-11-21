package com.acme.shortener.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side of the analytics tables. Ranges are [from, toExclusive). */
@Repository
public class ClickStatsRepository {

  public record ReferrerClicks(String host, long clicks) {}

  private final JdbcClient jdbc;

  public ClickStatsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** From the pre-aggregated table: one row per day that had clicks. */
  public Map<LocalDate, Long> dailyClicks(long linkId, LocalDate from, LocalDate to) {
    Map<LocalDate, Long> days = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT day, clicks FROM click_daily
            WHERE link_id = :linkId AND day BETWEEN :from AND :to ORDER BY day
            """)
        .param("linkId", linkId)
        .param("from", from)
        .param("to", to)
        .query(rs -> {
          days.put(rs.getObject("day", LocalDate.class), rs.getLong("clicks"));
        });
    return days;
  }

  public List<ReferrerClicks> topReferrers(
      long linkId, Instant from, Instant toExclusive, int limit) {
    return jdbc.sql(
            """
            SELECT referrer_host, count(*) AS clicks FROM click_event
            WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
              AND referrer_host IS NOT NULL
            GROUP BY referrer_host ORDER BY clicks DESC, referrer_host LIMIT :limit
            """)
        .param("linkId", linkId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(toExclusive))
        .param("limit", limit)
        .query((rs, n) -> new ReferrerClicks(rs.getString("referrer_host"), rs.getLong("clicks")))
        .list();
  }

  /** Family label → clicks, most clicks first. */
  public Map<String, Long> byUaFamily(long linkId, Instant from, Instant toExclusive) {
    Map<String, Long> families = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT ua_family, count(*) AS clicks FROM click_event
            WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
            GROUP BY ua_family ORDER BY clicks DESC, ua_family
            """)
        .param("linkId", linkId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(toExclusive))
        .query(rs -> {
          families.put(rs.getString("ua_family"), rs.getLong("clicks"));
        });
    return families;
  }

  /** Exact distinct visitor hashes: the fallback when Redis can't answer PFCOUNT. */
  public long distinctVisitors(long linkId, Instant from, Instant toExclusive) {
    return jdbc.sql(
            """
            SELECT count(DISTINCT visitor_hash) FROM click_event
            WHERE link_id = :linkId AND occurred_at >= :from AND occurred_at < :to
            """)
        .param("linkId", linkId)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(toExclusive))
        .query(Long.class)
        .single();
  }
}
