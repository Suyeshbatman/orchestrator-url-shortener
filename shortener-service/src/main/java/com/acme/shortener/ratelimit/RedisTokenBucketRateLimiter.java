package com.acme.shortener.ratelimit;

import java.time.Clock;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Shared buckets in Redis: a hash {@code rl:{scope}:{subject}} with {@code tokens} and {@code ts},
 * updated by one atomic Lua script. The key expires once the bucket would be full again.
 */
public final class RedisTokenBucketRateLimiter implements RateLimiter {

  static final String KEY_PREFIX = "rl:";

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> SCRIPT =
      RedisScript.of(new ClassPathResource("ratelimit/token-bucket.lua"), List.class);

  private final StringRedisTemplate redis;
  private final Clock clock;

  public RedisTokenBucketRateLimiter(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  @Override
  public Decision tryConsume(String key, Limit limit) {
    long ttlMillis = limit.period().toMillis() + 1_000;
    List<?> result =
        redis.execute(
            SCRIPT,
            List.of(KEY_PREFIX + key),
            Long.toString(limit.capacity()),
            Double.toString(limit.tokensPerMilli()),
            Long.toString(clock.millis()),
            Long.toString(ttlMillis));
    if (result == null || result.size() != 2) {
      throw new IllegalStateException("unexpected rate limit script result: " + result);
    }
    boolean allowed = "1".equals(String.valueOf(result.get(0)));
    double tokens = Double.parseDouble(String.valueOf(result.get(1)));
    return Decision.of(limit, allowed, tokens);
  }
}
