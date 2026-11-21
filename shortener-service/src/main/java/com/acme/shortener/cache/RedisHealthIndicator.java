package com.acme.shortener.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis is optional (NFR-2): when it is unreachable the service keeps working, so this reports
 * {@code DEGRADED} (HTTP 200, see application.yaml) instead of DOWN, and readiness doesn't include
 * it. Replaces Boot's own Redis indicator, which would report DOWN.
 */
public final class RedisHealthIndicator implements HealthIndicator {

  public static final Status DEGRADED = new Status("DEGRADED", "Redis unavailable; degraded mode");

  private final RedisConnectionFactory connections;
  private final CircuitBreaker breaker;

  public RedisHealthIndicator(RedisConnectionFactory connections, CircuitBreaker breaker) {
    this.connections = connections;
    this.breaker = breaker;
  }

  @Override
  public Health health() {
    try (RedisConnection connection = connections.getConnection()) {
      connection.ping();
      return Health.up().withDetail("breaker", breaker.getState().name()).build();
    } catch (RuntimeException e) {
      return Health.status(DEGRADED)
          .withDetail("breaker", breaker.getState().name())
          .withDetail("error", e.getClass().getSimpleName())
          .withDetail(
              "impact",
              "cache served from the DB; create limited per instance; redirect unlimited;"
                  + " unique visitors counted from the DB")
          .build();
    }
  }
}
