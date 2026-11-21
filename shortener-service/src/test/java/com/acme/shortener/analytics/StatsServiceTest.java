package com.acme.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Range validation runs before any lookup; the rest is covered by AnalyticsIT. */
class StatsServiceTest {

  private final StatsService service =
      new StatsService(
          null, null, null, Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC), 90);

  @Test
  void fromAfterToIsRejected() {
    assertThatThrownBy(() ->
            service.stats(
                "abc", "me", LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-01")))
        .isInstanceOf(InvalidStatsRangeException.class)
        .hasMessageContaining("'from' must not be after 'to'");
  }

  @Test
  void moreThanMaxDaysIsRejected() {
    // 2026-01-01..2026-03-31 is exactly 90 days, so one day earlier is 91.
    assertThatThrownBy(() ->
            service.stats(
                "abc", "me", LocalDate.parse("2025-12-31"), LocalDate.parse("2026-03-31")))
        .isInstanceOf(InvalidStatsRangeException.class)
        .hasMessageContaining("91 days");
  }

  @Test
  void defaultFromBeforeAnExplicitFromIsStillValidated() {
    // to defaults to today (2026-03-31); a from after it is rejected.
    assertThatThrownBy(() -> service.stats("abc", "me", LocalDate.parse("2026-04-01"), null))
        .isInstanceOf(InvalidStatsRangeException.class);
  }
}
