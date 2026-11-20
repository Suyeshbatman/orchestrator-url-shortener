package com.acme.shortener.cache;

import java.util.Optional;

/** Read-through cache for redirects (ADR-0003). Empty means a miss at every level. */
public interface LinkCache {

  Optional<CacheEntry> get(String code);

  /** Caches an active link. Overwrites any negative entry for the same code. */
  void putFound(String code, String originalUrl);

  void putNotFound(String code);

  /** Removes the code from every level. (Not called on delete in the baseline: BF-1.) */
  void evict(String code);
}
