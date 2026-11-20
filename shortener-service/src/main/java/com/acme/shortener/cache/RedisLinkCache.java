package com.acme.shortener.cache;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * L2 in Redis. One key per code: {@code link:{code}} holding {@code U:<url>} for a link or {@code
 * N} for a negative entry, so a later create simply overwrites a negative entry.
 */
public final class RedisLinkCache implements RemoteLinkCache {

  static final String KEY_PREFIX = "link:";
  private static final String FOUND = "U:";
  private static final String NOT_FOUND = "N";

  private final StringRedisTemplate redis;

  public RedisLinkCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<CacheEntry> get(String code) {
    String value = redis.opsForValue().get(KEY_PREFIX + code);
    if (value == null) {
      return Optional.empty();
    }
    if (value.startsWith(FOUND)) {
      return Optional.of(new CacheEntry.Found(value.substring(FOUND.length())));
    }
    return Optional.of(CacheEntry.NOT_FOUND);
  }

  @Override
  public void put(String code, CacheEntry entry, Duration ttl) {
    String value =
        switch (entry) {
          case CacheEntry.Found found -> FOUND + found.originalUrl();
          case CacheEntry.NotFound notFound -> NOT_FOUND;
        };
    redis.opsForValue().set(KEY_PREFIX + code, value, ttl);
  }

  @Override
  public void delete(String code) {
    redis.delete(KEY_PREFIX + code);
  }
}
