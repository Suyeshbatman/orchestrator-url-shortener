package com.acme.shortener.analytics;

import com.acme.shortener.link.Link;
import com.acme.shortener.link.LinkService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Owner-only link stats. Days are UTC. Reads ~90 pre-aggregated rows plus two grouped scans. */
public final class StatsService {

  static final int DEFAULT_DAYS = 30;
  static final int TOP_REFERRERS = 5;

  private final LinkService links;
  private final ClickStatsRepository stats;
  private final UniqueVisitors uniques;
  private final Clock clock;
  private final int maxRangeDays;

  public StatsService(
      LinkService links,
      ClickStatsRepository stats,
      UniqueVisitors uniques,
      Clock clock,
      int maxRangeDays) {
    this.links = links;
    this.stats = stats;
    this.uniques = uniques;
    this.clock = clock;
    this.maxRangeDays = maxRangeDays;
  }

  /**
   * @param from first day, inclusive; defaults to 30 days before {@code to}
   * @param to last day, inclusive; defaults to today (UTC)
   */
  public LinkStats stats(String code, String owner, LocalDate from, LocalDate to) {
    LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
    if (start.isAfter(end)) {
      throw new InvalidStatsRangeException("'from' must not be after 'to'");
    }
    long days = ChronoUnit.DAYS.between(start, end) + 1;
    if (days > maxRangeDays) {
      throw new InvalidStatsRangeException(
          "range is " + days + " days; at most " + maxRangeDays + " allowed");
    }

    Link link = links.findOwned(code, owner); // 404 unless the caller owns it
    long id = link.id();
    Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toExclusive = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    Map<LocalDate, Long> daily = stats.dailyClicks(id, start, end);
    List<LinkStats.DayClicks> byDay =
        start
            .datesUntil(end.plusDays(1))
            .map(d -> new LinkStats.DayClicks(d, daily.getOrDefault(d, 0L)))
            .toList();
    long total = byDay.stream().mapToLong(LinkStats.DayClicks::clicks).sum();
    long unique =
        total == 0
            ? 0
            : uniques
                .count(id, start, end)
                .orElseGet(() -> stats.distinctVisitors(id, fromInstant, toExclusive));

    return new LinkStats(
        link.code(),
        start,
        end,
        total,
        unique,
        byDay,
        stats.topReferrers(id, fromInstant, toExclusive, TOP_REFERRERS),
        stats.byUaFamily(id, fromInstant, toExclusive));
  }
}
