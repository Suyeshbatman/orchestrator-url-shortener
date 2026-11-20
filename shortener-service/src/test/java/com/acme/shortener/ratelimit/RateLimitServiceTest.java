package com.acme.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitServiceTest {

  private static final Limit CREATE = new Limit(2, Duration.ofMinutes(1));
  private static final Limit REDIRECT = new Limit(3, Duration.ofMinutes(1));

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final SwitchableRemote remote = new SwitchableRemote(clock);
  private final InMemoryTokenBucketRateLimiter local =
      new InMemoryTokenBucketRateLimiter(clock, 100, Duration.ofMinutes(1));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CircuitBreaker breaker =
      CircuitBreaker.of(
          "redis",
          CircuitBreakerConfig.custom()
              .slidingWindowSize(4)
              .minimumNumberOfCalls(4)
              .failureRateThreshold(50)
              .waitDurationInOpenState(Duration.ofMinutes(10))
              .build());
  private final RateLimitService service =
      new RateLimitService(remote, local, breaker, CREATE, REDIRECT, meters);

  @Test
  void usesRedisWithScopedKeysAndPerScopeLimits() {
    assertThat(service.check(RateLimitScope.CREATE, "tester").limit()).isEqualTo(2);
    assertThat(service.check(RateLimitScope.REDIRECT, "abc").limit()).isEqualTo(3);

    assertThat(remote.keys).hasValue(2);
    assertThat(remote.lastKey).isEqualTo("redirect:abc");
  }

  @Test
  void createIsRejectedOnceTheBucketIsEmpty() {
    service.check(RateLimitScope.CREATE, "tester");
    service.check(RateLimitScope.CREATE, "tester");

    assertThat(service.check(RateLimitScope.CREATE, "tester").allowed()).isFalse();
    assertThat(requests("create", "allowed")).isEqualTo(2);
    assertThat(requests("create", "rejected")).isEqualTo(1);
  }

  @Test
  void createFallsBackToTheLocalBucketWhenRedisFails() {
    remote.failing = true;

    assertThat(service.check(RateLimitScope.CREATE, "tester").allowed()).isTrue();
    assertThat(service.check(RateLimitScope.CREATE, "tester").allowed()).isTrue();
    Decision third = service.check(RateLimitScope.CREATE, "tester");

    assertThat(third.allowed()).isFalse(); // still limited while degraded
    assertThat(third.metered()).isTrue();
    assertThat(meters.counter("ratelimit.fallback", "scope", "create", "mode", "local").count())
        .isEqualTo(3);
  }

  @Test
  void redirectFailsOpenWhenRedisFails() {
    remote.failing = true;

    for (int i = 0; i < 10; i++) {
      assertThat(service.check(RateLimitScope.REDIRECT, "abc")).isEqualTo(Decision.UNMETERED);
    }
    assertThat(meters.counter("ratelimit.fallback", "scope", "redirect", "mode", "open").count())
        .isEqualTo(10);
  }

  @Test
  void openBreakerSkipsRedisEntirely() {
    remote.failing = true;
    for (int i = 0; i < 4; i++) {
      service.check(RateLimitScope.REDIRECT, "abc");
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    int callsBefore = remote.keys.get();
    service.check(RateLimitScope.CREATE, "tester");

    assertThat(remote.keys).hasValue(callsBefore);
  }

  private double requests(String scope, String result) {
    return meters.counter("ratelimit.requests", "scope", scope, "result", result).count();
  }

  /** Stands in for Redis: a real in-memory bucket that can be switched to fail. */
  static final class SwitchableRemote implements RateLimiter {
    private final InMemoryTokenBucketRateLimiter delegate;
    final AtomicInteger keys = new AtomicInteger();
    volatile String lastKey;
    volatile boolean failing;

    SwitchableRemote(MutableClock clock) {
      this.delegate = new InMemoryTokenBucketRateLimiter(clock, 100, Duration.ofMinutes(1));
    }

    @Override
    public Decision tryConsume(String key, Limit limit) {
      keys.incrementAndGet();
      lastKey = key;
      if (failing) {
        throw new IllegalStateException("redis down");
      }
      return delegate.tryConsume(key, limit);
    }
  }
}
