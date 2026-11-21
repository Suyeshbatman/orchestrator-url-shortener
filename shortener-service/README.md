# shortener-service

A production-style URL shortener: the **target system** that the orchestrator's agents change. It is
Spring Boot 4.2 on Java 21 with Postgres (Spring Data JDBC + Flyway) and Redis. Redis is optional:
when it is down, the service keeps working in a degraded mode.

**Port:** the shortener runs on **8085** and the orchestrator on **8086**, because the usual 8080/8081
couldn't be used. Override with `SERVER_PORT`.

## Contents
- [Run it](#run-it)
- [API](#api)
- [Errors](#errors)
- [Configuration](#configuration)
- [Operations](#operations)
- [Design notes](#design-notes)

## Run it

You need Java 21 and Docker. Run all commands from the repository root; on Mac/Linux use `./mvnw`
instead of `.\mvnw.cmd`.

### With Docker Compose (Postgres on 5434, Redis on 6379)
1. Copy `.env.example` to `.env`, then set:
   - `SHORTENER_CODE_SECRET`: a random string of at least 32 characters.
   - `SHORTENER_API_KEYS`: `name:sha256(key)`, comma-separated. The service stores only the hash,
     so pick a key, hash it, and keep the key itself to send as `X-API-Key`:
     ```bash
     printf '%s' 'my-dev-key' | sha256sum                       # bash
     ```
     ```powershell
     $b=[Text.Encoding]::UTF8.GetBytes('my-dev-key'); -join ([Security.Cryptography.SHA256]::Create().ComputeHash($b) | % { $_.ToString('x2') })
     ```
     For example `SHORTENER_API_KEYS=dev:<that hex>`.
2. Start the infrastructure and the service:
   ```
   docker compose up -d
   .\mvnw.cmd -pl shortener-service spring-boot:run
   ```
3. Check it: `curl http://localhost:8085/actuator/health/readiness`.

Add `--profile observability` to `docker compose up` for Prometheus (9090) and Grafana (3000).
`docker compose down` stops everything.

### With Testcontainers (no compose, throwaway data)
```
.\mvnw.cmd -pl shortener-service spring-boot:test-run
```
This starts disposable Postgres and Redis containers and uses the test configuration: the API key is
`test-key` (owner `tester`) and the base URL is `http://sho.rt`.

### Build and test
```
.\mvnw.cmd -q verify            # unit + integration tests (Docker required), coverage gate, format check
.\mvnw.cmd -q spotless:apply    # format the code
```

## API

The OpenAPI spec is served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, and a committed copy
at [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml).

Management endpoints (`/api/**`) need `X-API-Key`. **A link is visible only to the key that created
it**: other keys get `404`, so link existence isn't revealed. The examples assume:

```bash
B=http://localhost:8085
KEY=my-dev-key
```

### Create a link: `POST /api/v1/links`
```bash
curl -i -X POST $B/api/v1/links \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f9c2a1e-order-42' \
  -d '{"url":"https://www.wikipedia.org/wiki/URL_shortening"}'
```
```http
HTTP/1.1 201
Location: /api/v1/links/QEIOSX7
Idempotent-Replayed: false
RateLimit-Limit: 60
RateLimit-Remaining: 59
RateLimit-Reset: 1
```
```json
{
  "code": "QEIOSX7",
  "shortUrl": "http://localhost:8085/QEIOSX7",
  "originalUrl": "https://www.wikipedia.org/wiki/URL_shortening",
  "customAlias": false,
  "status": "ACTIVE",
  "createdAt": "2026-11-20T23:58:43.123456Z",
  "updatedAt": "2026-11-20T23:58:43.123456Z"
}
```
- `customAlias` (optional) picks the code. It must be 4–32 characters of `A-Za-z0-9_-`. It is unique
  case-insensitively and can't be a reserved word (`api`, `actuator`, `admin`, `health`, `swagger-ui`).
  ```bash
  curl -X POST $B/api/v1/links -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
    -d '{"url":"https://example.com/launch","customAlias":"launch-2026"}'
  ```
- **The URL** must be `http` or `https`, at most 2048 characters, with no `user:pass@`. It must not
  point at private, loopback, link-local or similar addresses (checked through DNS), or at the
  shortener itself.
- **`Idempotency-Key`** (optional, 1–128 printable ASCII characters) makes retries safe:
  - The same key and body replay the stored response with `Idempotent-Replayed: true`, and no second
    link is created.
  - The same key with a different body returns `422`.
  - Keys are kept for 24 hours, separately for each API key.

### Get a link: `GET /api/v1/links/{code}`
```bash
curl $B/api/v1/links/QEIOSX7 -H "X-API-Key: $KEY"
```
Returns the same body as create. A deleted link still returns `200`, with `"status":"DELETED"`.

### Delete a link: `DELETE /api/v1/links/{code}`
```bash
curl -i -X DELETE $B/api/v1/links/QEIOSX7 -H "X-API-Key: $KEY"    # 204, also when already deleted
```
The delete is soft: the row is kept and marked `DELETED`. ⚠️ A deleted link can keep redirecting for
a while. That is [BF-1](../docs/known-issues.md), a known issue left in on purpose.

### Redirect: `GET /{code}`
```bash
curl -i $B/QEIOSX7
```
```http
HTTP/1.1 302
Location: https://www.wikipedia.org/wiki/URL_shortening
Cache-Control: max-age=0, private
```
Public, no API key. An unknown or deleted code returns `404`. Each redirect records a click in the
background; the redirect never waits for analytics.

### Stats: `GET /api/v1/links/{code}/stats?from=&to=`
```bash
curl "$B/api/v1/links/QEIOSX7/stats?from=2026-11-01&to=2026-11-20" -H "X-API-Key: $KEY"
```
```json
{
  "code": "QEIOSX7",
  "from": "2026-11-01",
  "to": "2026-11-20",
  "totalClicks": 81,
  "uniqueVisitors": 3,
  "clicksByDay": [{"day": "2026-11-01", "clicks": 0}, "…", {"day": "2026-11-20", "clicks": 81}],
  "topReferrers": [{"host": "news.ycombinator.com", "clicks": 50}],
  "byUaFamily": {"Firefox": 50, "Chrome": 30, "Bot": 1}
}
```
- Days are UTC and both ends are included. The default range is the last 30 days and the maximum is
  90 days.
- Clicks appear about one second after the redirect.
- `uniqueVisitors` is approximate (HyperLogLog). Visitors are identified by a hash that rotates daily,
  so someone who visits on two days counts twice.
- Referrers are reduced to their host. Browser families are `Chrome`, `Edge`, `Firefox`, `Safari`,
  `Bot` and `Other`.
- No raw IP address is ever stored.

### Rate limits
Token buckets:
- **Create:** 60 per minute per API key.
- **Redirect:** 600 per minute per visitor.

Allowed responses carry `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` (seconds).
Over the limit, the service returns `429` with `Retry-After`.

When Redis is down, create falls back to a per-instance limit and redirects are not limited.

### Common response headers
- **`X-Correlation-Id`:** echoed if you send a safe value (1–64 characters of `A-Za-z0-9._-`),
  otherwise generated. It also appears in every log line and in error bodies.
- **Security headers:** `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, a strict
  `Content-Security-Policy`, and `Cache-Control: no-store` on `/api/**`.

## Errors

Every error is RFC 9457 Problem Details (`application/problem+json`). The `type` URI is stable, so
assert on it rather than on the message.

```json
{
  "type": "/problems/invalid-url",
  "title": "Invalid URL",
  "status": 400,
  "detail": "url must not point at a private or local network address",
  "instance": "/api/v1/links",
  "reason": "PRIVATE_ADDRESS",
  "correlationId": "5b0c…"
}
```

| `type` | Status | When |
|---|---|---|
| `/problems/validation-failed` | 400 | Invalid fields (with an `errors[{field,message}]` list), or a parameter of the wrong type |
| `/problems/malformed-request` | 400 | Missing body or invalid JSON |
| `/problems/invalid-url` | 400 | URL rejected. `reason` is one of `TOO_LONG`, `MALFORMED`, `SCHEME_NOT_ALLOWED`, `MISSING_HOST`, `USERINFO_NOT_ALLOWED`, `NON_CANONICAL_IP`, `PRIVATE_ADDRESS`, `UNRESOLVABLE_HOST`, `SELF_REFERENCE` |
| `/problems/invalid-alias` | 400 | Alias has the wrong length or characters, or is reserved |
| `/problems/invalid-idempotency-key` | 400 | Idempotency-Key is not 1–128 printable ASCII characters |
| `/problems/invalid-stats-range` | 400 | `from` is after `to`, or the range is over 90 days |
| `/problems/unauthorized` | 401 | Missing or unknown `X-API-Key` (sent with `WWW-Authenticate`) |
| `/problems/link-not-found` | 404 | Unknown code, a deleted link on redirect, or another key's link |
| `/problems/alias-taken` | 409 | Alias already in use (case-insensitive) |
| `/problems/idempotency-key-reused` | 422 | Same Idempotency-Key with a different body |
| `/problems/rate-limited` | 429 | Bucket empty. See `Retry-After` |
| `/problems/internal-error` | 500 | Unexpected error. It is logged with the correlation id; no internals are returned |

## Configuration

Settings live in [`application.yaml`](src/main/resources/application.yaml). Values come from
environment variables first, then the repo-root `.env`, then the defaults. Any setting can be
overridden by its environment variable form, for example
`SHORTENER_RATE_LIMIT_CREATE_CAPACITY=120`.

### Environment variables
| Variable | Default | Meaning |
|---|---|---|
| `SHORTENER_CODE_SECRET` | — (**required**) | Key for code generation, at least 32 characters. Also keys the visitor hash. Never commit it. |
| `SHORTENER_API_KEYS` | empty (every `/api` call gets 401) | `name:sha256hex,…` |
| `SHORTENER_BASE_URL` | `http://localhost:8085` | Prefix for `shortUrl`. Links to this host are rejected. |
| `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `jdbc:postgresql://localhost:5434/shortener` / `app` / — | Database |
| `REDIS_URL` | `redis://localhost:6379` | Cache, rate limits, unique visitors |
| `SHORTENER_LOG_FORMAT` | empty (readable console) | `logstash` gives one JSON object per line, including the MDC (`correlationId`) |
| `SERVER_PORT` | `8085` | HTTP port |

### `shortener.*` settings
| Property | Default | Meaning |
|---|---|---|
| `redirect.status` | `302` | `301`–`308`. 302 keeps analytics and deletes working ([ADR-0002](../docs/adr/0002-redirect-302.md)) |
| `code.length` | `7` | Generated code length (4–10) |
| `code.feistel-rounds` | `4` | At least 3 |
| `alias.min` / `alias.max` | `4` / `32` | Alias length |
| `alias.pattern` | `^[A-Za-z0-9_-]+$` | Allowed alias characters |
| `alias.reserved` | `api, actuator, admin, health, swagger-ui` | Aliases that can't be used |
| `url.max-length` | `2048` | Longest accepted URL |
| `url.allowed-schemes` | `http, https` | |
| `url.block-private-networks` | `true` | SSRF protection |
| `url.dns-timeout` | `500ms` | DNS lookup timeout during validation |
| `cache.l1-max-size` | `10000` | In-process (Caffeine) entries |
| `cache.l1-ttl` / `cache.l2-ttl` | `5m` / `24h` | Caffeine / Redis TTL |
| `cache.negative-ttl` | `60s` | How long "not found" is cached |
| `cache.redis-breaker.sliding-window-size` | `20` | Calls in the circuit breaker's window |
| `cache.redis-breaker.minimum-number-of-calls` | `10` | Calls before the breaker may open |
| `cache.redis-breaker.failure-rate-threshold` | `50` | Failure percentage that opens it |
| `cache.redis-breaker.wait-duration-in-open-state` | `10s` | Time before a half-open probe |
| `cache.redis-breaker.permitted-calls-in-half-open-state` | `3` | Probe calls |
| `idempotency.ttl` | `24h` | How long Idempotency-Keys are kept |
| `rate-limit.enabled` | `true` | |
| `rate-limit.create-capacity` / `create-period` | `60` / `1m` | Per API key |
| `rate-limit.redirect-capacity` / `redirect-period` | `600` / `1m` | Per visitor |
| `rate-limit.local-max-keys` | `10000` | Bound on the in-memory fallback buckets |
| `analytics.queue-capacity` | `10000` | Clicks buffered in memory. Beyond that they are dropped and counted |
| `analytics.batch-size` / `flush-interval` | `500` / `1s` | Writer batching |
| `analytics.hll-ttl` | `400d` | Lifetime of the Redis unique-visitor keys |
| `analytics.max-range-days` | `90` | Longest stats range |

## Operations

### Health (`/actuator/health`)
- **Liveness:** `/actuator/health/liveness` reports only whether the app is running.
- **Readiness:** `/actuator/health/readiness` needs the database. Redis is deliberately left out.
- **Overall:** `/actuator/health` reports Redis as `DEGRADED` (still HTTP 200) when it is down.

### Metrics (`/actuator/prometheus`)
Every metric carries the tag `application="shortener-service"`.

| Metric | Tags | |
|---|---|---|
| `shortener_create_seconds` | `outcome` = created, failed | Timer with histogram |
| `shortener_resolve_seconds` | `outcome` = found, not_found, error | Timer with histogram |
| `cache_requests_total` | `level` = l1, l2; `result` = hit, miss | |
| `cache_redis_fallback_total` | | Redis calls skipped or failed |
| `ratelimit_requests_total` | `scope` = create, redirect; `result` = allowed, rejected | |
| `ratelimit_fallback_total` | `scope`; `mode` = local, open | |
| `analytics_events_{published,dropped,written,failed}_total` | | Click pipeline |
| `analytics_queue_size` | | Gauge |
| `http_server_requests_seconds` | | Spring's per-endpoint timer |

### When Redis is down
| Feature | Behaviour |
|---|---|
| Redirect | Served from the DB; the breaker skips Redis, so requests don't wait for Redis timeouts |
| Create rate limit | Per instance (N instances allow up to N × 60/min) |
| Redirect rate limit | Off (fails open) |
| Unique visitors | Counted exactly from the DB |
| Health | `redis: DEGRADED`; readiness stays `UP` |

### Shutdown
Shutdown is graceful: in-flight requests finish, then the click writer flushes its queue, with up to
20 seconds per phase. A crash loses the clicks still in memory (at-most-once delivery).

## Design notes
- [ADR-0001: Code generation (sequence → Feistel → Base62)](../docs/adr/0001-code-generation.md)
- [ADR-0002: Redirect with 302](../docs/adr/0002-redirect-302.md)
- [ADR-0003: Two-level cache with negative caching](../docs/adr/0003-cache-strategy.md)
- [ADR-0004: Analytics, async and at-most-once](../docs/adr/0004-analytics-at-most-once.md)
- [Known issues (BF-1)](../docs/known-issues.md)
- Full reasoning: [DESIGN_RATIONALE.md](../docs/plan/DESIGN_RATIONALE.md)

### Package layout (by feature)
| Package | Contents |
|---|---|
| `link` | Link model, repository, service, controllers (API and redirect) |
| `code` | Base62, Feistel permutation, code generator, alias rules |
| `url` | URL and SSRF validation, DNS resolver |
| `auth` | API key registry and interceptor |
| `idempotency` | Idempotency-Key storage and replay |
| `cache` | L1/L2 cache, Redis health |
| `ratelimit` | Token buckets (Redis Lua and in-memory), policy, visitor fingerprint |
| `analytics` | Click queue, batch writer, HyperLogLog, stats |
| `api` | Problem Details, correlation id, security headers |
| `config` | Typed properties and wiring |
