package com.acme.shortener.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 Caffeine (in-process, ~ns) → L2 Redis (shared, ~sub-ms) → caller falls back to the DB
 * (ADR-0003, DESIGN_RATIONALE §3.3).
 *
 * <p>Every Redis call goes through the "redis" circuit breaker. A failing or slow Redis (timeouts
 * are set on the client) counts as a miss; once the breaker opens, Redis is skipped entirely until
 * the half-open probe succeeds, so a Redis outage costs DB load, not latency.
 */
public final class TieredLinkCache implements LinkCache {

  private static final Logger log = LoggerFactory.getLogger(TieredLinkCache.class);

  public record Settings(long l1MaxSize, Duration l1Ttl, Duration l2Ttl, Duration negativeTtl) {}

  private final Cache<String, CacheEntry.Found> l1Found;
  private final Cache<String, CacheEntry.NotFound> l1NotFound;
  private final RemoteLinkCache l2;
  private final CircuitBreaker breaker;
  private final Settings settings;

  private final Counter l1Hits;
  private final Counter l1Misses;
  private final Counter l2Hits;
  private final Counter l2Misses;
  private final Counter redisFallbacks;

  public TieredLinkCache(
      RemoteLinkCache l2,
      CircuitBreaker breaker,
      Settings settings,
      MeterRegistry meters,
      Ticker ticker) {
    this.l2 = l2;
    this.breaker = breaker;
    this.settings = settings;
    // Negative entries live in their own L1 cache so their short TTL never shortens real entries.
    this.l1Found =
        Caffeine.newBuilder()
            .maximumSize(settings.l1MaxSize())
            .expireAfterWrite(settings.l1Ttl())
            .ticker(ticker)
            .build();
    this.l1NotFound =
        Caffeine.newBuilder()
            .maximumSize(settings.l1MaxSize())
            .expireAfterWrite(settings.negativeTtl())
            .ticker(ticker)
            .build();
    this.l1Hits = counter(meters, "cache.requests", "level", "l1", "result", "hit");
    this.l1Misses = counter(meters, "cache.requests", "level", "l1", "result", "miss");
    this.l2Hits = counter(meters, "cache.requests", "level", "l2", "result", "hit");
    this.l2Misses = counter(meters, "cache.requests", "level", "l2", "result", "miss");
    this.redisFallbacks = Counter.builder("cache.redis.fallback").register(meters);
    breaker
        .getEventPublisher()
        .onStateTransition(
            e -> log.warn("Redis circuit breaker {}", e.getStateTransition())); // once per change
  }

  @Override
  public Optional<CacheEntry> get(String code) {
    CacheEntry local = l1Found.getIfPresent(code);
    if (local == null) {
      local = l1NotFound.getIfPresent(code);
    }
    if (local != null) {
      l1Hits.increment();
      return Optional.of(local);
    }
    l1Misses.increment();

    Optional<CacheEntry> remote = guarded(() -> l2.get(code), Optional.empty());
    if (remote.isPresent()) {
      l2Hits.increment();
      putLocal(code, remote.get()); // promote to L1
    } else {
      l2Misses.increment();
    }
    return remote;
  }

  @Override
  public void putFound(String code, String originalUrl) {
    CacheEntry.Found entry = new CacheEntry.Found(originalUrl);
    putLocal(code, entry);
    guarded(
        () -> {
          l2.put(code, entry, settings.l2Ttl());
          return null;
        },
        null);
  }

  @Override
  public void putNotFound(String code) {
    putLocal(code, CacheEntry.NOT_FOUND);
    guarded(
        () -> {
          l2.put(code, CacheEntry.NOT_FOUND, settings.negativeTtl());
          return null;
        },
        null);
  }

  @Override
  public void evict(String code) {
    invalidateLocal(code);
    guarded(
        () -> {
          l2.delete(code);
          return null;
        },
        null);
  }

  /** Drops the code from this instance's L1 only. */
  public void invalidateLocal(String code) {
    l1Found.invalidate(code);
    l1NotFound.invalidate(code);
  }

  /** Drops all of this instance's L1 (tests, ops). */
  public void invalidateAllLocal() {
    l1Found.invalidateAll();
    l1NotFound.invalidateAll();
  }

  private void putLocal(String code, CacheEntry entry) {
    switch (entry) {
      case CacheEntry.Found found -> {
        l1NotFound.invalidate(code);
        l1Found.put(code, found);
      }
      case CacheEntry.NotFound notFound -> {
        l1Found.invalidate(code);
        l1NotFound.put(code, notFound);
      }
    }
  }

  /** Runs a Redis call through the breaker; any failure (or open breaker) yields the fallback. */
  private <T> T guarded(Supplier<T> call, T fallback) {
    try {
      return breaker.executeSupplier(call);
    } catch (RuntimeException e) {
      redisFallbacks.increment();
      log.debug("Redis call skipped or failed, falling back: {}", e.toString());
      return fallback;
    }
  }

  private static Counter counter(MeterRegistry meters, String name, String... tags) {
    return Counter.builder(name).tags(tags).register(meters);
  }
}
