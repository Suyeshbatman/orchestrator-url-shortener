package com.acme.shortener.config;

import com.acme.shortener.ratelimit.InMemoryTokenBucketRateLimiter;
import com.acme.shortener.ratelimit.Limit;
import com.acme.shortener.ratelimit.RateLimitInterceptor;
import com.acme.shortener.ratelimit.RateLimitService;
import com.acme.shortener.ratelimit.RedisTokenBucketRateLimiter;
import com.acme.shortener.ratelimit.VisitorFingerprint;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  @Bean
  RateLimitService rateLimitService(
      StringRedisTemplate redis,
      CircuitBreaker redisCircuitBreaker,
      Clock clock,
      ShortenerProperties properties,
      MeterRegistry meters) {
    ShortenerProperties.RateLimit r = properties.rateLimit();
    Limit create = new Limit(r.createCapacity(), r.createPeriod());
    Limit redirect = new Limit(r.redirectCapacity(), r.redirectPeriod());
    Duration idle = max(r.createPeriod(), r.redirectPeriod());
    return new RateLimitService(
        new RedisTokenBucketRateLimiter(redis, clock),
        new InMemoryTokenBucketRateLimiter(clock, r.localMaxKeys(), idle),
        redisCircuitBreaker,
        create,
        redirect,
        meters);
  }

  /** Keyed with the code secret; the "visitor" prefix keeps the two uses apart. */
  @Bean
  VisitorFingerprint visitorFingerprint(ShortenerProperties properties, Clock clock) {
    return new VisitorFingerprint(properties.code().secret(), clock);
  }

  @Bean
  RateLimitInterceptor rateLimitInterceptor(
      RateLimitService rateLimitService, VisitorFingerprint visitorFingerprint) {
    return new RateLimitInterceptor(rateLimitService, visitorFingerprint);
  }

  private static Duration max(Duration a, Duration b) {
    return a.compareTo(b) >= 0 ? a : b;
  }
}
