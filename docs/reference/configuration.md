# Configuration reference

Both apps are Spring Boot. A setting comes from, in falling precedence:
1. a real environment variable;
2. the repo-root `.env` (imported with `spring.config.import: optional:file:../.env[.properties]`);
3. `application.yaml`.

Any property can also be set with its relaxed environment form, e.g. `SHORTENER_CACHE_L1_TTL=2m`.
Invalid values fail startup, because the shortener's properties are `@Validated`. Durations use
Spring's format (`500ms`, `5m`, `24h`, `400d`).

Secrets (`*_SECRET`, `*_PASSWORD`, API keys) belong in the environment or `.env`, never in YAML or
Git. `.env.example` holds placeholders only. Its `VISITOR_HASH_SALT_SECRET` is not read by any code:
the visitor fingerprint is keyed with `shortener.code.secret`.

## Shortener (`shortener-service`)

### Infrastructure
| Property | Environment | Default | |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8085` | |
| `spring.datasource.url` | `POSTGRES_URL` | `jdbc:postgresql://localhost:5434/shortener` | |
| `spring.datasource.username` / `password` | `POSTGRES_USER` / `POSTGRES_PASSWORD` | `app` / — | |
| `spring.datasource.hikari.maximum-pool-size` | | `20` | |
| `spring.datasource.hikari.connection-timeout` | | `2000` ms | fail fast instead of queueing requests |
| `spring.data.redis.url` | `REDIS_URL` | `redis://localhost:6379` | |
| `spring.data.redis.timeout` | | `200ms` | a slow Redis counts as a miss, never as latency |
| `spring.lifecycle.timeout-per-shutdown-phase` | | `20s` | graceful shutdown; the click writer flushes last |
| `logging.structured.format.console` | `SHORTENER_LOG_FORMAT` | (plain) | `logstash` for JSON logs |
| `spring.threads.virtual.enabled` | | `true` | requests run on virtual threads |

### Application (`shortener.*`, `ShortenerProperties`)
| Property | Default | Range | |
|---|---|---|---|
| `base-url` (`SHORTENER_BASE_URL`) | `http://localhost:8085` | required | prefix of `shortUrl`; links to this host are refused |
| `api-keys` (`SHORTENER_API_KEYS`) | empty | `name:sha256hex,…` | empty: every `/api` request is 401 (a warning is logged) |
| `redirect.status` | `302` | 301–308 | ADR-0002 |
| `code.length` | `7` | 4–10 | 62⁷ ≈ 3.5 × 10¹² codes |
| `code.secret` (`SHORTENER_CODE_SECRET`) | — | ≥ 32 characters, required | Feistel key, also the key of the daily-rotating visitor fingerprint (HMAC). Changing it changes every future code; existing ones stay valid |
| `code.feistel-rounds` | `4` | ≥ 3 | |
| `alias.min` / `alias.max` | `4` / `32` | max ≤ 128 | |
| `alias.pattern` | `^[A-Za-z0-9_-]+$` | | |
| `alias.reserved` | `api, actuator, admin, health, swagger-ui` | | |
| `url.max-length` | `2048` | ≥ 16 | |
| `url.allowed-schemes` | `http, https` | | |
| `url.block-private-networks` | `true` | | resolve and refuse private, loopback, link-local and multicast targets |
| `url.dns-timeout` | `500ms` | | |
| `cache.l1-max-size` | `10000` | ≥ 1 | Caffeine, per instance |
| `cache.l1-ttl` | `5m` | | also the bound on cross-instance staleness |
| `cache.l2-ttl` | `24h` | | Redis |
| `cache.negative-ttl` | `60s` | | how long "no such code" is cached (stops scans from reaching the DB) |
| `cache.redis-breaker.*` | window `20`, minimum calls `10`, failure rate `50` %, open `10s`, half-open calls `3` | | Resilience4j breaker shared by the cache, the rate limiter and the HyperLogLogs |
| `idempotency.ttl` | `24h` | | |
| `rate-limit.enabled` | `true` | | `false` logs a warning |
| `rate-limit.create-capacity` / `create-period` | `60` / `1m` | | per API key: burst 60, then 1/s |
| `rate-limit.redirect-capacity` / `redirect-period` | `600` / `1m` | | per visitor: burst 600, then 10/s |
| `rate-limit.local-max-keys` | `10000` | | the in-process fallback's bucket count (Caffeine-bounded) |
| `analytics.queue-capacity` | `10000` | ≥ 1 | the bounded click queue; overflow is dropped (ADR-0004) |
| `analytics.batch-size` | `500` | ≥ 1 | |
| `analytics.flush-interval` | `1s` | | |
| `analytics.hll-ttl` | `400d` | | HyperLogLog key lifetime |
| `analytics.max-range-days` | `90` | 1–400 | |

### Actuator
- **Endpoints:** `health`, `info` and `prometheus` are exposed.
- **Health:** shows its components. Readiness is `readinessState` plus `db`; Redis is optional, and
  `DEGRADED` maps to HTTP 200.
- **Metrics:** every meter is tagged `application=shortener-service`.
- **In production:** serve these on `management.server.port`, and set
  `springdoc.swagger-ui.enabled=false` (`docs/security.md`, S-6).

## Orchestrator (`orchestrator-app`, `orchestrator.*`, `OrchestratorProperties`)
| Property | Environment | Default | |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8086` | |
| `spring.datasource.url` | `ORCH_POSTGRES_URL` | `jdbc:postgresql://localhost:5434/orchestrator` | user and password as for the shortener |
| `config-dir` | `ORCH_CONFIG_DIR` | `../config` | `workflows/`, `policies.yml`, `autonomy.yml` (paths are relative to `orchestrator-app/`) |
| `scenarios-dir` | `ORCH_SCENARIOS_DIR` | `../scenarios` | |
| `data-dir` | `ORCH_DATA_DIR` | `../.orchestrator` | replay fixtures of non-scenario runs |
| `workspace-root` | `ORCH_WORKSPACE_ROOT` | `../.agent-worktrees` | one clone per run |
| `source-repo` | `ORCH_SOURCE_REPO` | `..` | what runs clone and publish to. **Must be bare** to publish (`git clone --bare`) |
| `base-branch` | `ORCH_BASE_BRANCH` | `main` | |
| `recover-on-startup` | | `true` | resume unfinished runs from their logs |
| `safe-stop-grace` | | `30s` | how long in-flight attempts get on a halt |
| `operator-keys` / `approver-keys` | `ORCH_OPERATOR_KEYS` / `ORCH_APPROVER_KEYS` | empty | `name:sha256hex,…` |
| `validation-sandbox` | `ORCH_VALIDATION_SANDBOX` | `auto` | `auto`: the Docker sandbox for unreviewed model output (live and record); `docker`: always; `none`: never |

### LLM (read from the environment by `LlmGateways.Config`)
| Variable | Default | |
|---|---|---|
| `ORCH_LLM_MODE` | `replay` | `replay`, `record` (live calls saved as fixtures) or `live` |
| `ANTHROPIC_API_KEY` | — | required for `record` and `live`. Redacted from every prompt; never logged |
| `ORCH_LLM_MODEL` | `claude-sonnet-5-5` | |
| `ORCH_LLM_FALLBACK_MODEL` | — | tried once when the primary model stays unavailable |

The gateway's timeouts are fixed in code (`AnthropicLlmGateway.Settings`): a 60 s base plus time for
the token limit at 40 tokens/s, 3 attempts with exponential backoff from 1 s, and a circuit breaker
(window 10, 50 % failure rate, 30 s open).

### The validation sandbox (`DockerSandbox.Settings.DEFAULTS`, in code)
| | |
|---|---|
| Daemon image | `docker:29.7.2-dind` (privileged, inside Docker's VM; private network) |
| Build image | `eclipse-temurin:21.0.11_10-jdk`, running the workspace's `mvnw` |
| Maven cache | volume `orchestrator-sandbox-m2` |
| Limits | 4 CPUs, 6 GB, 4096 processes; all capabilities dropped; `no-new-privileges` |
| Daemon start timeout | 90 s |

## Workflow, policies and autonomy
These are YAML under `config/`, loaded and validated at run start. Their hashes are recorded in
`RUN_CREATED`. See [workflows-and-policies.md](workflows-and-policies.md).

## CLI (`orchestrator-cli`)
```
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=<command> [options]"
```
| Command | Options | |
|---|---|---|
| `run-scenario <id>` | `--llm replay|record|handwritten|live` (`replay`), `--timeout 60m`, `--sandbox auto|docker|none` | one scenario in one process, with in-memory stores and scripted humans |
| `record-fixtures <id>` | as above | `run-scenario --llm handwritten`: replies from `scenarios/<id>/replies/`, missing prompts written to `replies/pending/` |
| `demo` | `--url http://localhost:8086`, `--timeout 20m` | the three scenarios through the API, using its own Postgres (compose project `orchestrator-demo`, port 5435) |
| `verify-audit <runId>` | `--key`, `--url` | |
| `metrics` | `--key`, `--url` | |

The CLI reads the environment overlaid on the repo-root `.env`; real variables win.

## Local infrastructure (`docker-compose.yml`, `.env`)
| Variable | Default | |
|---|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `app` / required / `shortener` | `docker/postgres/init.sql` also creates `orchestrator` |
| `POSTGRES_PORT` | `5434` | host port |
| `REDIS_PORT` | `6379` | |
| `PROMETHEUS_PORT` / `GRAFANA_PORT` / `GRAFANA_ADMIN_PASSWORD` | `9090` / `3000` / — | `--profile observability` |
