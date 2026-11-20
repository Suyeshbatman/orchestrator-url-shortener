package com.acme.shortener.cache;

/** What the cache knows about a code: it redirects somewhere, or it is known not to exist. */
public sealed interface CacheEntry {

  record Found(String originalUrl) implements CacheEntry {}

  /** Negative entry: unknown or deleted code. Short TTL; stops scan-DoS reaching the DB. */
  record NotFound() implements CacheEntry {}

  NotFound NOT_FOUND = new NotFound();
}
