package com.acme.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RateLimitHeadersTest {

  @Test
  void allowedHasRateLimitHeadersButNoRetryAfter() {
    Decision d = new Decision(true, true, 60, 59, Duration.ZERO, Duration.ofMillis(1_001));

    HttpHeaders h = RateLimitHeaders.of(d);

    assertThat(h.getFirst("RateLimit-Limit")).isEqualTo("60");
    assertThat(h.getFirst("RateLimit-Remaining")).isEqualTo("59");
    assertThat(h.getFirst("RateLimit-Reset")).isEqualTo("2"); // rounded up
    assertThat(h.containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
  }

  @Test
  void rejectedHasRetryAfterOfAtLeastOneSecond() {
    Decision d = new Decision(false, true, 60, 0, Duration.ofMillis(10), Duration.ofSeconds(60));

    assertThat(RateLimitHeaders.of(d).getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void unmeteredHasNoHeaders() {
    assertThat(RateLimitHeaders.of(Decision.UNMETERED).isEmpty()).isTrue();
  }
}
