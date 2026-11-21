package com.acme.shortener.analytics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Unique visitors per link and UTC day in Redis HyperLogLogs, {@code hll:{linkId}:{yyyyMMdd}}: ≤ 12
 * KB per key, ~0.81% standard error. PFCOUNT over several day keys counts their union. Every call
 * goes through the shared "redis" breaker; failures are reported as "no answer", never thrown.
 */
public final class UniqueVisitors {

  private static final Logger log = LoggerFactory.getLogger(UniqueVisitors.class);
  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  private final StringRedisTemplate redis;
  private final CircuitBreaker breaker;
  private final Duration ttl;

  public UniqueVisitors(StringRedisTemplate redis, CircuitBreaker breaker, Duration ttl) {
    this.redis = redis;
    this.breaker = breaker;
    this.ttl = ttl;
  }

  static String key(long linkId, LocalDate day) {
    return "hll:" + linkId + ":" + DAY.format(day);
  }

  /** PFADD + EXPIRE. Returns false if Redis was unavailable (the DB still has the hashes). */
  public boolean add(long linkId, LocalDate day, Collection<String> visitorHashes) {
    String key = key(linkId, day);
    try {
      breaker.executeRunnable(() -> {
        redis.opsForHyperLogLog().add(key, visitorHashes.toArray(String[]::new));
        redis.expire(key, ttl);
      });
      return true;
    } catch (RuntimeException e) {
      log.debug("PFADD {} skipped: {}", key, e.toString());
      return false;
    }
  }

  /**
   * Approximate distinct visitors over the days {@code from..to}; empty if Redis is unavailable.
   */
  public Optional<Long> count(long linkId, LocalDate from, LocalDate to) {
    String[] keys = from.datesUntil(to.plusDays(1)).map(d -> key(linkId, d)).toArray(String[]::new);
    try {
      return Optional.of(breaker.executeSupplier(() -> redis.opsForHyperLogLog().size(keys)));
    } catch (RuntimeException e) {
      log.debug("PFCOUNT for link {} skipped: {}", linkId, e.toString());
      return Optional.empty();
    }
  }
}
