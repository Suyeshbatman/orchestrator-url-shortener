package com.acme.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class InMemoryTokenBucketRateLimiterTest {

  private static final Limit FIVE_PER_MINUTE = new Limit(5, Duration.ofMinutes(1));

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final InMemoryTokenBucketRateLimiter limiter =
      new InMemoryTokenBucketRateLimiter(clock, 1_000, Duration.ofMinutes(1));

  @Test
  void allowsABurstUpToCapacityThenRejects() {
    for (int i = 4; i >= 0; i--) {
      Decision d = limiter.tryConsume("k", FIVE_PER_MINUTE);
      assertThat(d.allowed()).isTrue();
      assertThat(d.remaining()).isEqualTo(i);
      assertThat(d.limit()).isEqualTo(5);
    }

    Decision rejected = limiter.tryConsume("k", FIVE_PER_MINUTE);
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.remaining()).isZero();
    assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(12)); // one token per 12s
    assertThat(rejected.resetAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void refillsContinuouslyAtCapacityPerPeriod() {
    drain("k", 5);

    clock.advance(Duration.ofSeconds(11));
    assertThat(limiter.tryConsume("k", FIVE_PER_MINUTE).allowed()).isFalse();
    clock.advance(Duration.ofSeconds(1));
    assertThat(limiter.tryConsume("k", FIVE_PER_MINUTE).allowed()).isTrue();
    assertThat(limiter.tryConsume("k", FIVE_PER_MINUTE).allowed()).isFalse();
  }

  @Test
  void refillNeverExceedsCapacity() {
    drain("k", 5);
    clock.advance(Duration.ofHours(1));

    assertThat(drain("k", 10)).isEqualTo(5);
  }

  @Test
  void keysHaveSeparateBuckets() {
    drain("a", 5);

    assertThat(limiter.tryConsume("a", FIVE_PER_MINUTE).allowed()).isFalse();
    assertThat(limiter.tryConsume("b", FIVE_PER_MINUTE).allowed()).isTrue();
  }

  @Test
  void clockGoingBackwardsDoesNotRefill() {
    drain("k", 5);
    clock.advance(Duration.ofMinutes(-10));
    clock.advance(Duration.ofSeconds(30)); // still before the last refill

    assertThat(limiter.tryConsume("k", FIVE_PER_MINUTE).allowed()).isFalse();
  }

  @Test
  void numberOfBucketsIsBounded() {
    InMemoryTokenBucketRateLimiter small =
        new InMemoryTokenBucketRateLimiter(clock, 10, Duration.ofMinutes(1));
    for (int i = 0; i < 1_000; i++) {
      small.tryConsume("k" + i, FIVE_PER_MINUTE);
    }

    assertThat(small.size()).isLessThanOrEqualTo(10);
  }

  /** 100 threads race for one bucket; with no time passing exactly capacity get through. */
  @Test
  void concurrentCallersNeverExceedCapacity() throws Exception {
    Limit limit = new Limit(50, Duration.ofMinutes(1));
    AtomicInteger allowed = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(100)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 100; t++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              for (int i = 0; i < 10; i++) {
                if (limiter.tryConsume("hot", limit).allowed()) {
                  allowed.incrementAndGet();
                }
              }
              return null;
            }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }

    assertThat(allowed).hasValue(50);
  }

  /** Over any schedule, allowed ≤ capacity + tokens refilled in the elapsed time. */
  @Property(tries = 200)
  void neverAllowsMoreThanCapacityPlusRefill(
      @ForAll @Size(max = 200) List<@IntRange(min = 0, max = 5_000) Integer> gapsMillis) {
    MutableClock c = new MutableClock(Instant.EPOCH);
    InMemoryTokenBucketRateLimiter l =
        new InMemoryTokenBucketRateLimiter(c, 10, Duration.ofMinutes(1));
    Limit limit = new Limit(3, Duration.ofSeconds(10));
    long elapsed = 0;
    int allowed = 0;
    for (int gap : gapsMillis) {
      c.advance(Duration.ofMillis(gap));
      elapsed += gap;
      if (l.tryConsume("k", limit).allowed()) {
        allowed++;
      }
    }

    assertThat((double) allowed).isLessThanOrEqualTo(3 + elapsed * limit.tokensPerMilli());
  }

  private int drain(String key, int attempts) {
    int allowed = 0;
    for (int i = 0; i < attempts; i++) {
      if (limiter.tryConsume(key, FIVE_PER_MINUTE).allowed()) {
        allowed++;
      }
    }
    return allowed;
  }
}
