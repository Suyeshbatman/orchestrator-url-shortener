package com.acme.shortener.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-endpoint policy (DESIGN_RATIONALE §3.4). Redis is asked first, through the shared "redis"
 * circuit breaker. If it fails or the breaker is open: create uses the local bucket (creating links
 * is the main abuse vector, so it stays limited), redirect fails open (blocking redirects would
 * break every link).
 */
public final class RateLimitService {

  private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

  private final RateLimiter redis;
  private final RateLimiter local;
  private final CircuitBreaker breaker;
  private final Map<RateLimitScope, Limit> limits;
  private final Map<RateLimitScope, Counter> allowed = new EnumMap<>(RateLimitScope.class);
  private final Map<RateLimitScope, Counter> rejected = new EnumMap<>(RateLimitScope.class);
  private final Map<RateLimitScope, Counter> fallbacks = new EnumMap<>(RateLimitScope.class);

  public RateLimitService(
      RateLimiter redis,
      RateLimiter local,
      CircuitBreaker breaker,
      Limit createLimit,
      Limit redirectLimit,
      MeterRegistry meters) {
    this.redis = redis;
    this.local = local;
    this.breaker = breaker;
    this.limits =
        Map.of(RateLimitScope.CREATE, createLimit, RateLimitScope.REDIRECT, redirectLimit);
    for (RateLimitScope scope : RateLimitScope.values()) {
      allowed.put(scope, requests(meters, scope, "allowed"));
      rejected.put(scope, requests(meters, scope, "rejected"));
      fallbacks.put(
          scope,
          Counter.builder("ratelimit.fallback")
              .tags("scope", scope.key(), "mode", scope == RateLimitScope.CREATE ? "local" : "open")
              .register(meters));
    }
  }

  /** Takes a token for {@code subject} (API key name or visitor fingerprint) in {@code scope}. */
  public Decision check(RateLimitScope scope, String subject) {
    Limit limit = limits.get(scope);
    String key = scope.key() + ":" + subject;
    Decision decision;
    try {
      decision = breaker.executeSupplier(() -> redis.tryConsume(key, limit));
    } catch (RuntimeException e) {
      fallbacks.get(scope).increment();
      log.debug("Rate limiter fallback for {}: {}", scope, e.toString());
      decision =
          switch (scope) {
            case CREATE -> local.tryConsume(key, limit);
            case REDIRECT -> Decision.UNMETERED;
          };
    }
    (decision.allowed() ? allowed : rejected).get(scope).increment();
    return decision;
  }

  private static Counter requests(MeterRegistry meters, RateLimitScope scope, String result) {
    return Counter.builder("ratelimit.requests")
        .tags("scope", scope.key(), "result", result)
        .register(meters);
  }
}
