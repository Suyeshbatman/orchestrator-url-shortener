package com.acme.shortener.config;

import com.acme.shortener.cache.RedisLinkCache;
import com.acme.shortener.cache.TieredLinkCache;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class CacheConfig {

  /** Shared by every Redis caller so they all see the same Redis health. */
  @Bean
  CircuitBreaker redisCircuitBreaker(ShortenerProperties properties) {
    ShortenerProperties.RedisBreaker b = properties.cache().redisBreaker();
    return CircuitBreaker.of(
        "redis",
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(b.slidingWindowSize())
            .minimumNumberOfCalls(b.minimumNumberOfCalls())
            .failureRateThreshold(b.failureRateThreshold())
            .waitDurationInOpenState(b.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(b.permittedCallsInHalfOpenState())
            .build());
  }

  @Bean
  TieredLinkCache linkCache(
      StringRedisTemplate redis,
      CircuitBreaker redisCircuitBreaker,
      ShortenerProperties properties,
      MeterRegistry meters) {
    ShortenerProperties.Cache c = properties.cache();
    return new TieredLinkCache(
        new RedisLinkCache(redis),
        redisCircuitBreaker,
        new TieredLinkCache.Settings(c.l1MaxSize(), c.l1Ttl(), c.l2Ttl(), c.negativeTtl()),
        meters,
        Ticker.systemTicker());
  }
}
