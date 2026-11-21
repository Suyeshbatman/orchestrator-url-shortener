# ADR-0004: Click analytics are asynchronous, batched and at-most-once

- **Status:** Accepted
- **Date:** 2026-11-20
- **Scope:** `shortener-service`, package `analytics`

## Context
Redirects must be fast (NFR-1), and every click should be recorded (FR-5). Writing to the DB on
every click would roughly double redirect latency. It would also tie redirect availability to
analytics write capacity: a slow insert would mean a slow redirect.

## Decision
1. **Publish without waiting.** After a successful redirect, the request thread makes a non-blocking
   `offer()` into a **bounded `ArrayBlockingQueue`** (capacity 10,000).
   - A full queue **drops** the click and counts it in `analytics.events.dropped`. An unbounded queue
     would end in an OOM under a spike and take redirects down with it.
   - The `ClickEventPublisher` port is the seam where a durable log can replace the in-memory queue.
2. **Write in batches.** One background virtual thread drains up to 500 clicks, or whatever arrived
   within 1 s, and writes them in one transaction:
   - a JDBC batch insert into `click_event` (raw rows, for drill-down and re-aggregation);
   - an upsert into `click_daily` (`clicks = clicks + EXCLUDED.clicks`), so stats read about 90 rows
     instead of scanning raw events.

   N round-trips become about N/500. Clicks are queued by code, and the writer resolves link ids for
   the whole batch in one query.
3. **Count unique visitors with HyperLogLog.** After commit, the writer runs `PFADD` on
   `hll:{linkId}:{yyyyMMdd}`, with a 400-day expiry. Each key is at most 12 KB with about 0.81 %
   standard error. `PFCOUNT` over several day keys counts their union. If Redis is down, stats count
   distinct hashes exactly from `click_event`.
4. **Privacy.**
   - Visitors are identified by HMAC-SHA256(server secret, UTC day | IP | user agent). No raw IP is
     stored, and hashes from different days can't be linked.
   - Referrers are reduced to their host; the path and query can carry personal data.
   - User agents are reduced to a browser family.
5. **Shutdown.** The writer stops after the web server, then flushes what is still queued.

## Consequences
- ✅ The redirect path never touches the analytics tables and costs one in-memory `offer()`.
- ✅ Memory stays flat under any traffic, and loss is **counted**, not silent.
- ✅ Stats queries are cheap thanks to pre-aggregation.
- ⚠️ **Limitation L-3: at-most-once delivery.**
  - Clicks still in memory are lost on a crash (a graceful shutdown flushes them).
  - A batch that fails to write is dropped and counted in `analytics.events.failed`.
  - Exactly-once would need a durable log (Kafka, Redis Streams) and idempotent consumers. The port
    makes that a drop-in upgrade.
- ⚠️ **Stats lag** by about one flush interval (1 s).
- ⚠️ **Unique visitors are per day.** The visitor hash rotates daily for privacy, so a visitor seen
  on two days counts twice in a multi-day range. The HLL union is still correct: it removes
  duplicates within a day, not across days. That fits marketing-style stats; billing would need
  exact, longer-lived identifiers.
- ⚠️ Uniques are approximate (HLL) when Redis answers and exact when the DB fallback answers, so the
  number can shift slightly between the two.
- ⚠️ In production, `click_event` would be partitioned by month, with a retention policy.
