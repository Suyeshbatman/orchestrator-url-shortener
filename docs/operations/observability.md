# Observability

Both apps expose Micrometer metrics at `/actuator/prometheus`. `docker compose --profile observability
up -d` starts Prometheus (`:9090`, scraping both apps) and Grafana (`:3000`). Meter names below are
Micrometer's; Prometheus shows them with dots as underscores and with `_total` / `_seconds` suffixes,
e.g. `cache.requests` becomes `cache_requests_total`.

## Shortener metrics
| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `shortener.resolve` | timer (histogram) | `outcome`: `found`, `not_found`, `error` | redirect lookups; p95/p99 for the latency SLO |
| `shortener.create` | timer (histogram) | `outcome`: `created`, `failed` | link creation |
| `cache.requests` | counter | `level`: `l1`/`l2`, `result`: `hit`/`miss` | hit ratio per level |
| `cache.redis.fallback` | counter | | Redis calls skipped or failed: the breaker is open or Redis is slow or down |
| `ratelimit.requests` | counter | `scope`: `create`/`redirect`, `result`: `allowed`/`rejected` | |
| `ratelimit.fallback` | counter | `scope`, `mode`: `local` (create) / `open` (redirect) | limiter decisions made without Redis |
| `analytics.events.published` | counter | | clicks queued |
| `analytics.events.dropped` | counter | | clicks dropped because the queue was full (ADR-0004: at-most-once) |
| `analytics.queue.size` | gauge | | current queue depth (capacity 10 000) |
| `analytics.events.written` / `failed` | counter | | clicks persisted, or lost on a DB error |
| `analytics.events.unknown-code` | counter | | clicks for a code with no link row (e.g. deleted mid-flight) |
| `http.server.requests` | timer | `uri`, `method`, `status`, `outcome` | Spring's per-endpoint metrics |
| `jvm.*`, `hikaricp.*`, `process.*` | | | runtime, connection pool, process |

Every meter is tagged `application=shortener-service`.

## Orchestrator metrics (`MetricsRecorder`, from the event stream)
| Metric | Type | Tags |
|---|---|---|
| `orchestrator.stage.executions` | counter | `stage`, `outcome` |
| `orchestrator.stage.retries` | counter | `stage` |
| `orchestrator.fallbacks` | counter | `stage` |
| `orchestrator.rollbacks` | counter | `stage` |
| `orchestrator.replans` | counter | `trigger` (`answers`, `amendment`, …) |
| `orchestrator.approvals.wait` | timer | `stage`, `decision`: `granted`/`rejected` |
| `orchestrator.run.duration` | timer | `outcome`: `completed`/`failed` |
| `orchestrator.llm.calls` / `orchestrator.llm.tokens` | counter | `stage` |
| `orchestrator.policy.denials` | counter | `stage` |
| `orchestrator.budget.exceeded` | counter | `budget` |

`GET /api/metrics/summary` computes the reliability summary from the event logs, the source of truth,
so it survives restarts and can be recomputed for any set of runs. It reports run and stage success
rates, retry rate, rollbacks, MTTR, end-to-end p50/p95 with and without approval wait, and approval
wait. The definitions are in `docs/reference/api.md`.

## Suggested alerts
| Alert | Condition (PromQL sketch) | Why |
|---|---|---|
| Redirect latency | `histogram_quantile(0.99, sum by (le) (rate(shortener_resolve_seconds_bucket[5m]))) > 0.05` | the hot path is a cache hit; p99 is under 5 ms on one client |
| Redirect errors | `rate(shortener_resolve_seconds_count{outcome="error"}[5m]) > 0` | errors mean the DB is unreachable on a cache miss |
| Redis degraded | `rate(cache_redis_fallback_total[5m]) > 0` for 5m | the breaker is open; the DB carries the load |
| Cache hit ratio | `sum(rate(cache_requests_total{result="hit"}[15m])) / sum(rate(cache_requests_total[15m])) < 0.8` | cold cache or scan traffic |
| Click loss | `rate(analytics_events_dropped_total[5m]) > 0` | the writer can't keep up or the DB is slow |
| Rate limiting in fallback | `rate(ratelimit_fallback_total[5m]) > 0` | redirects are unmetered while Redis is down |
| Readiness | `/actuator/health/readiness` ≠ 200 | the DB is down; take the instance out of rotation |
| Stuck approvals | approvals `PENDING` longer than the team's SLA (`GET /api/approvals?status=PENDING`) | runs wait for humans |
| Policy denials | `increase(orchestrator_policy_denials_total[1h]) > 0` | an agent tried something forbidden; review the run |
| Budget stops | `increase(orchestrator_budget_exceeded_total[1h]) > 0` | a run safe-stopped |

## Logs
- **Shortener:** plain by default. JSON (Logstash format, via Boot's structured logging) with
  `SHORTENER_LOG_FORMAT=logstash`. Every line of a request carries `correlationId` (MDC), which is
  also returned as `X-Correlation-Id` and put in every Problem Details body. Raw client IPs are never
  logged; CMP-001 enforces this for agent-written code.
- **Orchestrator:** the authoritative record is the event log, not the application log.
  `GET /api/runs/{id}/events` has every decision, with the actor, the reasons and the artifact
  references. The CLI writes it to `.orchestrator/scenarios/<id>/events.jsonl`.

## Health
| App | Liveness | Readiness |
|---|---|---|
| shortener | `/actuator/health/liveness` | `/actuator/health/readiness`: `db` (Redis is not required) |
| orchestrator | `/actuator/health/liveness` | `/actuator/health/readiness` |

The shortener's Redis indicator reports `DEGRADED` (HTTP 200) instead of `DOWN`, because Redis is an
optimisation, not a dependency.
