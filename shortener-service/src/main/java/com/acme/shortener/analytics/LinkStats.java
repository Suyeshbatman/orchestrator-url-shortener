package com.acme.shortener.analytics;

import com.acme.shortener.analytics.ClickStatsRepository.ReferrerClicks;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Stats for one link over the UTC days {@code from..to} (inclusive).
 *
 * @param uniqueVisitors distinct visitor hashes. The hash rotates daily, so a visitor seen on two
 *     days counts twice. HyperLogLog estimate (~0.81% error), or exact from the DB when Redis is
 *     down
 * @param clicksByDay every day in the range, zero-filled
 * @param byUaFamily label → clicks, most first
 */
public record LinkStats(
    String code,
    LocalDate from,
    LocalDate to,
    long totalClicks,
    long uniqueVisitors,
    List<DayClicks> clicksByDay,
    List<ReferrerClicks> topReferrers,
    Map<String, Long> byUaFamily) {

  public record DayClicks(LocalDate day, long clicks) {}
}
