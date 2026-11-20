package com.acme.shortener.cache;

import java.time.Duration;
import java.util.Optional;

/** The shared L2 level (Redis). Implementations may throw; the caller guards them. */
public interface RemoteLinkCache {

  Optional<CacheEntry> get(String code);

  void put(String code, CacheEntry entry, Duration ttl);

  void delete(String code);
}
