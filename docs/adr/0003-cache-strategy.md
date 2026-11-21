# ADR-0003: Two-level cache with negative caching and a Redis circuit breaker

- **Status:** Accepted. The baseline knowingly omits invalidation on delete (BF-1).
- **Date:** 2026-11-20
- **Scope:** `shortener-service`, package `cache` (`TieredLinkCache`)

## Context
Redirects are the hot path:
- **Read-heavy:** redirects outnumber creates 100:1 or more.
- **Skewed:** a few links get most of the traffic.
- **Fast:** NFR-1 asks for a p99 in milliseconds.

Postgres alone would be the bottleneck. Redis must not become a single point of failure, though
(NFR-2): if it is slow or down, redirects have to keep working.

## Decision
**Read path:** L1 Caffeine (in-process, about ns) → L2 Redis (shared, under 1 ms) → Postgres (ms).
- An L2 hit is promoted to L1.
- A DB hit fills both levels.

| | L1 (Caffeine) | L2 (Redis) |
|---|---|---|
| Found entry TTL | 5 min | 24 h |
| "Not found" TTL | 60 s (kept in a separate cache) | 60 s |
| Size | 10,000 entries per instance | key `link:{code}`, value `U:<url>` or `N` |

- **Negative caching:** "not found" is cached for 60 seconds. Without it, a bot scanning random codes
  turns every request into a DB query, which makes a cheap DoS. The short TTL limits staleness when a
  code is created right after a miss. That is rare, because codes look random (ADR-0001).
- **Fill after commit:** a create writes to the cache only once its transaction has committed, so a
  rollback never leaves a ghost link behind.
- **Circuit breaker:** every Redis call goes through one shared Resilience4j breaker named `redis`
  (window 20, opens at 50 % failures, 10 s before a half-open probe).
  - Redis has a 200 ms client timeout. A Redis failure is treated as a cache miss, never as an error.
  - Once the breaker is open, Redis is skipped entirely, so an outage costs DB load and not latency.
  - The Hikari pool (20) is sized for that fallback load. The rate limiter and the analytics
    unique-visitor counters share the same breaker.
- **Health:** an unreachable Redis reports `DEGRADED` (HTTP 200), and readiness doesn't depend on it.

## Invalidation
The baseline does **not** evict on delete. **This is BF-1, seeded on purpose** for the brownfield
scenario (see `docs/known-issues.md`). The intended fix:
1. On delete, evict the code from L2 and the local L1.
2. Publish a Redis pub/sub message so every instance evicts its L1.
3. Accept the remaining risk: if a message is lost, the 5-minute L1 TTL bounds staleness. A stricter
   option is a version check on L1 hits.
4. When link expiry is added, entry TTLs must not outlive the link (`ttl ≤ expiresAt − now`).

## Consequences
- ✅ Most redirects never leave the process. Hot links cost about the same as a hash-map lookup.
- ✅ A Redis outage is survivable and visible: `cache_redis_fallback_total`, the breaker's state
  logged as a WARN, and the `DEGRADED` health status.
- ⚠️ Instances can disagree for up to the L1 TTL until the BF-1 fix adds pub/sub invalidation.
- ⚠️ The DB carries the full read load while Redis is down, and the pool is sized for it.
