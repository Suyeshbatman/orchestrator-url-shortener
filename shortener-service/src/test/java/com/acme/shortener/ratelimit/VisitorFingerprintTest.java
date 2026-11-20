package com.acme.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VisitorFingerprintTest {

  private static final String SECRET = "test-only-feistel-secret-0123456789abcdef";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
  private final VisitorFingerprint fingerprints = new VisitorFingerprint(SECRET, clock);

  @Test
  void isStableWithinADayAndHidesTheIp() {
    String first = fingerprints.of("203.0.113.7", "Firefox");
    clock.advance(Duration.ofHours(13)); // 23:00 UTC, same day

    assertThat(fingerprints.of("203.0.113.7", "Firefox")).isEqualTo(first);
    assertThat(first).matches("[0-9a-f]{64}").doesNotContain("203");
  }

  @Test
  void rotatesAtUtcMidnight() {
    String today = fingerprints.of("203.0.113.7", "Firefox");
    clock.advance(Duration.ofHours(14)); // 00:00 UTC next day

    assertThat(fingerprints.of("203.0.113.7", "Firefox")).isNotEqualTo(today);
  }

  @Test
  void differsByIpAndByUserAgent() {
    String base = fingerprints.of("203.0.113.7", "Firefox");

    assertThat(fingerprints.of("203.0.113.8", "Firefox")).isNotEqualTo(base);
    assertThat(fingerprints.of("203.0.113.7", "Chrome")).isNotEqualTo(base);
  }

  @Test
  void dependsOnTheSecret() {
    VisitorFingerprint other = new VisitorFingerprint(SECRET + "x", clock);

    assertThat(other.of("203.0.113.7", "Firefox"))
        .isNotEqualTo(fingerprints.of("203.0.113.7", "Firefox"));
  }

  @Test
  void missingUserAgentIsAllowed() {
    assertThat(fingerprints.of("203.0.113.7", null)).isEqualTo(fingerprints.of("203.0.113.7", ""));
  }
}
