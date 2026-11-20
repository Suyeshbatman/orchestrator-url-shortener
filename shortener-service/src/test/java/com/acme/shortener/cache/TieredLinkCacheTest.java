package com.acme.shortener.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TieredLinkCacheTest {

  private static final TieredLinkCache.Settings SETTINGS =
      new TieredLinkCache.Settings(
          100, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofSeconds(60));

  private final AtomicLong nanos = new AtomicLong();
  private final FakeRemote remote = new FakeRemote();
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
  private final TieredLinkCache cache =
      new TieredLinkCache(remote, breaker, SETTINGS, meters, nanos::get);

  @Test
  void missEverywhereIsEmpty() {
    assertThat(cache.get("abc")).isEmpty();
    assertThat(count("l1", "miss")).isEqualTo(1);
    assertThat(count("l2", "miss")).isEqualTo(1);
  }

  @Test
  void putFoundWritesBothLevelsWithL2Ttl() {
    cache.putFound("abc", "https://example.com");

    assertThat(cache.get("abc")).contains(new CacheEntry.Found("https://example.com"));
    assertThat(count("l1", "hit")).isEqualTo(1);
    assertThat(remote.values).containsEntry("abc", new CacheEntry.Found("https://example.com"));
    assertThat(remote.ttls).containsEntry("abc", Duration.ofHours(24));
  }

  @Test
  void l2HitIsPromotedToL1() {
    remote.values.put("abc", new CacheEntry.Found("https://example.com"));

    assertThat(cache.get("abc")).isPresent();
    assertThat(cache.get("abc")).isPresent();

    assertThat(count("l2", "hit")).isEqualTo(1);
    assertThat(count("l1", "hit")).isEqualTo(1);
    assertThat(remote.gets).hasValue(1);
  }

  @Test
  void negativeEntriesUseTheShortTtl() {
    cache.putNotFound("nope");

    assertThat(cache.get("nope")).contains(CacheEntry.NOT_FOUND);
    assertThat(remote.ttls).containsEntry("nope", Duration.ofSeconds(60));

    advance(Duration.ofSeconds(61));
    remote.values.clear();
    assertThat(cache.get("nope")).isEmpty();
  }

  @Test
  void foundEntriesExpireFromL1AfterL1Ttl() {
    cache.putFound("abc", "https://example.com");
    remote.values.clear(); // only L1 left

    advance(Duration.ofMinutes(4));
    assertThat(cache.get("abc")).isPresent();
    advance(Duration.ofMinutes(2));
    assertThat(cache.get("abc")).isEmpty();
  }

  @Test
  void putFoundReplacesANegativeEntry() {
    cache.putNotFound("abc");

    cache.putFound("abc", "https://example.com");

    assertThat(cache.get("abc")).contains(new CacheEntry.Found("https://example.com"));
    assertThat(remote.values.get("abc")).isInstanceOf(CacheEntry.Found.class);
  }

  @Test
  void evictRemovesFromBothLevels() {
    cache.putFound("abc", "https://example.com");

    cache.evict("abc");

    assertThat(cache.get("abc")).isEmpty();
    assertThat(remote.values).doesNotContainKey("abc");
  }

  @Test
  void redisFailuresFallBackSilentlyAndOpenTheBreaker() {
    remote.failing = true;

    for (int i = 0; i < 4; i++) {
      assertThat(cache.get("k" + i)).isEmpty(); // a miss, not an exception
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    int callsBefore = remote.gets.get();
    assertThat(cache.get("another")).isEmpty();
    assertThat(remote.gets).hasValue(callsBefore); // open breaker: Redis not even tried
    assertThat(meters.counter("cache.redis.fallback").count()).isEqualTo(5);
  }

  @Test
  void l1KeepsWorkingWhileRedisIsDown() {
    cache.putFound("abc", "https://example.com");
    remote.failing = true;

    assertThat(cache.get("abc")).contains(new CacheEntry.Found("https://example.com"));
    cache.putFound("def", "https://example.org"); // L2 write fails, L1 still updated
    assertThat(cache.get("def")).isPresent();
  }

  @Test
  void invalidateLocalLeavesL2() {
    cache.putFound("abc", "https://example.com");

    cache.invalidateLocal("abc");

    assertThat(cache.get("abc")).isPresent();
    assertThat(count("l2", "hit")).isEqualTo(1);
  }

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  private double count(String level, String result) {
    return meters.counter("cache.requests", "level", level, "result", result).count();
  }

  /** In-memory L2 that can be switched to fail like an unreachable Redis. */
  static final class FakeRemote implements RemoteLinkCache {
    final Map<String, CacheEntry> values = new HashMap<>();
    final Map<String, Duration> ttls = new HashMap<>();
    final AtomicInteger gets = new AtomicInteger();
    boolean failing;

    @Override
    public Optional<CacheEntry> get(String code) {
      gets.incrementAndGet();
      failIfDown();
      return Optional.ofNullable(values.get(code));
    }

    @Override
    public void put(String code, CacheEntry entry, Duration ttl) {
      failIfDown();
      values.put(code, entry);
      ttls.put(code, ttl);
    }

    @Override
    public void delete(String code) {
      failIfDown();
      values.remove(code);
    }

    private void failIfDown() {
      if (failing) {
        throw new IllegalStateException("redis down");
      }
    }
  }
}
