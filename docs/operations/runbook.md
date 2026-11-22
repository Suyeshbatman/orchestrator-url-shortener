# Runbook

Operating the shortener and the orchestrator: deployment, routine tasks and incident playbooks.
Metrics and alerts are in [observability.md](observability.md), settings in
[../reference/configuration.md](../reference/configuration.md).

## Service overview
| | Shortener | Orchestrator |
|---|---|---|
| Port | 8085 | 8086 |
| State | Postgres (`link`, `idempotency_record`, `click_event`, `click_daily`); Redis (L2 cache, rate-limit buckets, HyperLogLogs) | Postgres (`workflow_event` is append-only; projections `run`, `stage_instance`, `approval`, `artifact`); Git workspaces on disk |
| Hard dependency | Postgres | Postgres, Git, Docker (validation builds) |
| Soft dependency | Redis: degrades, never fails | the LLM API (live mode only) |
| Stateless? | yes, apart from the in-process L1 cache and the click queue | no: one instance owns its active runs |
| Scale out | horizontally, behind a load balancer | one instance (no run leases yet) |

## SLOs (suggested)
| SLO | Target | Measured by |
|---|---|---|
| Redirect availability | 99.95 % non-5xx per 30 days | `http.server.requests{uri="/{code}"}` |
| Redirect latency, cache hit | p99 < 50 ms at the service | `shortener.resolve{outcome="found"}` (measured p99 4.8 ms on one client; `docs/testing.md`) |
| Create availability | 99.9 % | `shortener.create` |
| Click analytics | best effort, at-most-once (ADR-0004) | `analytics.events.dropped` |
| Orchestrator API | 99 % | `http.server.requests` |

## Deploying
1. **Build:** `.\mvnw.cmd -q verify` (tests, coverage gates, formatting), then deploy the
   `spring-boot` jars from `*/target/`.
2. **Migrations:** Flyway runs on startup. The shortener's migrations are additive, so an old
   instance keeps working against a migrated schema. Roll out one instance at a time.
3. **Shortener:** a rolling restart is safe.
   - Graceful shutdown (`server.shutdown: graceful`) drains requests, then the click writer flushes
     what is queued (20 s per phase).
   - Redis values stay compatible across versions (`U:<url>`).
4. **Orchestrator:** stop it, deploy, and start it. With `recover-on-startup: true`, every
   unfinished run is rebuilt from its event log, and the attempt that was in flight is retried.
   Prefer halting active runs first (`POST /api/runs/{id}/halt`) and resuming them after.
5. **Rollback:**
   - **Code:** redeploy the previous jar.
   - **Schema:** migrations are forward-only. The shortener's are additive, so the old code ignores
     new columns.

## Routine tasks
**Rotate an API key** (both apps use `name:sha256hex` lists):
1. Add the new key's hash under the same name, next to the old one:
   `SHORTENER_API_KEYS=app:<old>,app:<new>` (one name can hold several hashes).
2. Restart, move the clients over, then remove the old hash and restart again.

To hash a key: `printf %s "$KEY" | sha256sum`, or see `docs/setup.md`.

**Rotate the code secret** (`SHORTENER_CODE_SECRET`):
- **Existing codes keep working:** they're stored in `link.code`, and the secret only produces new
  ones.
- **Afterwards,** new codes come from a different permutation. A collision with an existing code is
  checked before insert, and the next id is taken.
- **Visitor fingerprints** also change, so unique-visitor counts restart from that day.

**Backups:**
- **Shortener Postgres:** `link` is the business data. The click tables can be rebuilt from nothing.
- **Orchestrator Postgres:** `workflow_event` and `artifact` are the audit record; the projections
  are rebuildable from the log. Back up with `pg_dump`.
- **Anchoring:** store the latest head hash of each run outside the database
  (`GET /api/runs/{id}/audit/verify` → `headHash`). That makes deleting the newest events
  detectable (`docs/security.md`, L-6).

**Clean up disk** (orchestrator):
- `orchestrator.workspace-root` holds one clone per run. Delete a finished run's folder once its
  branch is merged or no longer needed.
- The sandbox's Maven cache is the volume `orchestrator-sandbox-m2` (`docker volume rm …` to reset).

## Incident playbooks

### Redis down or slow (shortener)
**Symptoms:**
- `cache.redis.fallback` rising and `ratelimit.fallback` rising;
- health `DEGRADED`, still 200;
- higher DB load and latency on redirects.

**What the service does on its own:**
- the Redis breaker opens after half of the last 20 calls fail (at least 10 calls), and Redis is
  skipped for 10 s at a time, so cache misses go to Postgres;
- link creation rate-limits per instance;
- redirects are not rate-limited (fail open);
- unique-visitor counts come exactly from the DB.

**Do:**
1. Check the Redis host and memory.
2. Watch `hikaricp.connections.active`. If the pool (20) saturates, add shortener instances or
   raise the pool size, within the DB's limits.
3. When Redis is back, the breaker probes it half-open and closes by itself. Nothing to flush.

### Postgres down (shortener)
**Symptoms:**
- readiness fails, so the load balancer takes instances out;
- `shortener.resolve{outcome="error"}` and 5xx on cache misses.

**What still works:** cached redirects (L1 and L2) keep working until their TTL. Creates, stats and
uncached redirects fail with `500 internal-error`, and clicks queue up and are dropped once the
queue is full.

**Do:** restore the DB. Connections fail fast (2 s timeout), so recovery is immediate. Lost clicks
are not replayed, by design.

### Redirect latency or 429 spike
- **Check the share of rejections:** `ratelimit.requests{scope="redirect",result="rejected"}`. One
  fingerprint is limited to a 600 burst and 10/s.
- **A scan for codes** shows up as `shortener.resolve{outcome="not_found"}` and negative-cache hits.
  Negative entries live 60 s, so the DB is protected. Block at the edge if it persists.
- **Behind a proxy:** set `server.forward-headers-strategy` so the client IP is right. Never trust
  `X-Forwarded-For` directly.

### A deleted link still redirects
- **On `main`, this is known issue BF-1.** Delete doesn't evict the cache, so the link redirects
  until its L1 (5 min) and L2 (24 h) entries expire.
- **To stop one link now:** delete `link:<code>` in Redis and restart, or wait out the L1 TTL.
- **The fix:** scenario 7.2 (evict after commit). It isn't merged into `main`
  (`docs/known-issues.md`).

### A run is stuck in `AWAITING_HUMAN`
1. `GET /api/approvals?runId=<id>&status=PENDING` shows what it waits for.
   - **The reason starts with `human input needed`:** blocking questions. Answer them:
     `POST /api/runs/{id}/answers`.
   - **Otherwise:** an approval. An approver decides it: `POST /api/approvals/{approvalId}`.
2. If the stage has an approval timeout, the run safe-stops by itself when it passes. Resume it
   with `POST /api/runs/{id}/resume`.

### A run halted (`RUN_HALTED`)
Causes: an operator halt; a budget was exceeded (`BUDGET_EXCEEDED`); an approval timed out; or a
`highImpact` stage (publish) failed.

1. Read the last events: `GET /api/runs/{id}/events?afterSeq=<n>`.
2. Fix the cause. Budgets are set in the workflow and recorded in the run, so a budget stop means
   the run needs a new budget: start a new run. A publish failure is often a source repository that
   isn't bare, or a merge conflict on `main`.
3. Then `POST /api/runs/{id}/resume`, or `POST /api/runs/{id}/rollback {"toStage": …}` to redo
   from a checkpoint.

### A run failed with `ReplayMissingException`
- **What it means:** a replay run met a prompt with no fixture. Something that feeds the prompt
  changed: the shortener's code at the base commit, a prompt template, a schema, or the
  requirement or answer text.
- **Compare the requests:** the CLI saves the missing request under
  `.orchestrator/scenarios/<id>/replay-missing/`. Diff it against the fixture of the same agent in
  `scenarios/<id>/replay/`.
- **Check the base:** `scenario.yml` must say `base: phase-7`, and in the app, the source
  repository's `main` must be at that commit.
- **A deliberate change:** re-record with `record-fixtures <id>`, or `--llm record`.

### The validation stage fails in the sandbox
- **The summary says `the sandbox could not run the build: …`:**
  - Docker isn't running, or it can't pull `docker:29.7.2-dind` or `eclipse-temurin:21.0.11_10-jdk`;
  - or the private daemon didn't start within 90 s.

  Pull the images by hand, and check Docker has at least 6 GB of memory available.
- **The first sandboxed build is slow:** it fills the `orchestrator-sandbox-m2` volume. Later builds
  take about 1.5 minutes longer than on the host.
- **Leftovers:** containers named `orch-sbx-*` should never outlive a build. If they do (the process
  was killed), remove them with `docker rm -f` and `docker network rm`.

### The audit chain is broken
`GET /api/runs/{id}/audit/verify` returns `valid: false` with `firstBrokenSeq`.
- **Treat it as a security incident.** The table rejects `UPDATE`, `DELETE` and `TRUNCATE` by
  trigger, so a break means someone bypassed it: a superuser, or a restore from a different backup.
- **Preserve** the database, and compare with a backup and the anchored head hash.
- **Don't resume the run.** Start a new one.

### The orchestrator crashed mid-run
Start it again. Recovery replays each unfinished run's log, rebuilds its state, re-arms its timers,
and retries the attempt that was in flight. `RunRecoveryIT` covers this.

### LLM unavailable (live mode)
- **What happens:** the gateway retries 429s, 5xx and timeouts three times, then tries
  `ORCH_LLM_FALLBACK_MODEL` once if it's set. Its circuit breaker opens after repeated failures.
  Stages see a transient failure and retry with backoff.
- **When the run gives up:** a run can fail when the retries are exhausted. Resume it from its
  checkpoint, or start a new run.
- **`credit balance is too low`** is a billing refusal, so it isn't retried. Check which
  `ANTHROPIC_API_KEY` is in effect: a real environment variable beats `.env`.
