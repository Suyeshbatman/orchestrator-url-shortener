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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The Lua script against real Redis, with the clock under test control. No Spring context. */
@Testcontainers
class RedisTokenBucketRateLimiterIT {

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connections;
  private static StringRedisTemplate template;

  private static final Limit FIVE_PER_MINUTE = new Limit(5, Duration.ofMinutes(1));

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final RedisTokenBucketRateLimiter limiter =
      new RedisTokenBucketRateLimiter(template, clock);

  @BeforeAll
  static void connect() {
    connections =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
    connections.afterPropertiesSet();
    connections.start();
    template = new StringRedisTemplate(connections);
  }

  @AfterAll
  static void disconnect() {
    connections.destroy();
  }

  @Test
  void burstThenRejectThenRefill() {
    String key = "burst";
    for (int i = 4; i >= 0; i--) {
      Decision d = limiter.tryConsume(key, FIVE_PER_MINUTE);
      assertThat(d.allowed()).isTrue();
      assertThat(d.remaining()).isEqualTo(i);
    }
    Decision rejected = limiter.tryConsume(key, FIVE_PER_MINUTE);
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(12));

    clock.advance(Duration.ofSeconds(12));
    assertThat(limiter.tryConsume(key, FIVE_PER_MINUTE).allowed()).isTrue();
    assertThat(limiter.tryConsume(key, FIVE_PER_MINUTE).allowed()).isFalse();
  }

  @Test
  void stateIsAHashThatExpiresAfterAFullRefill() {
    limiter.tryConsume("ttl", FIVE_PER_MINUTE);

    String redisKey = RedisTokenBucketRateLimiter.KEY_PREFIX + "ttl";
    assertThat(template.<String, String>opsForHash().entries(redisKey))
        .containsEntry("tokens", "4")
        .containsKey("ts");
    assertThat(template.getExpire(redisKey)).isBetween(55L, 61L);
  }

  @Test
  void anInstanceWithAnOlderClockDoesNotRefillOrRewindTheBucket() {
    String key = "skew";
    for (int i = 0; i < 5; i++) {
      limiter.tryConsume(key, FIVE_PER_MINUTE);
    }
    MutableClock behind = new MutableClock(Instant.parse("2025-12-31T23:59:00Z"));
    RedisTokenBucketRateLimiter otherInstance = new RedisTokenBucketRateLimiter(template, behind);

    assertThat(otherInstance.tryConsume(key, FIVE_PER_MINUTE).allowed()).isFalse();
    clock.advance(Duration.ofSeconds(12)); // the bucket's own timestamp was not moved back
    assertThat(limiter.tryConsume(key, FIVE_PER_MINUTE).allowed()).isTrue();
  }

  /** The script is atomic: 100 threads, 1000 attempts, exactly capacity succeed. */
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
}
