# BUILD PLAN — Agentic SDLC Orchestrator + URL Shortener

> **Audience:** Claude Code running in IntelliJ, and you as the human owner.
> **Companion document:** `DESIGN_RATIONALE.md` explains *why* for every decision here. This file says *what* and *how*.
> **Timebox:** 3 days. Each phase ends with something that builds, passes its tests, and is committed.

---

## 0. How to use this plan with Claude Code

1. Create an empty folder `agentic-url-shortener`, open it in IntelliJ, run `git init`.
2. Copy `BUILD_PLAN.md` and `DESIGN_RATIONALE.md` into `docs/plan/`.
3. Start Claude Code and say:
   *"Read docs/plan/BUILD_PLAN.md and docs/plan/DESIGN_RATIONALE.md. Do Step 0.1 only, then stop and show me what you did."*
4. Do **one step at a time**. For each step:
   - Paste the step's **Prompt** block (or say "do Step X.Y from the plan").
   - For larger steps, ask Claude Code to use **plan mode** first (Shift+Tab), review the plan, then let it run.
   - Check the **Acceptance criteria** yourself. Run the listed commands.
   - Commit using the suggested message. Tag the milestones listed.
5. Run `/clear` between phases to keep context small. `CLAUDE.md` (Step 0.2) carries the rules across sessions.
6. **Human checkpoints (🧑‍⚖️)** are places where you review before going on. They mirror the governance the system itself enforces. You are dogfooding controlled autonomy.

Legend: 🧑‍⚖️ human checkpoint · ✅ acceptance criteria · 🏷️ git tag · ⚠️ risk to watch

---

## 1. Normalized Requirements

The original brief has been normalized into IDs that can be traced. Every step below cites the IDs it satisfies. Section 9 has the traceability matrix.

### 1.1 Product: URL Shortener Service (the *target system*)

| ID | Requirement |
|---|---|
| FR-1 | Create a short link from a long URL (`POST /api/v1/links`), with an optional custom alias. |
| FR-2 | Redirect `GET /{code}` → original URL (HTTP 302). |
| FR-3 | Get link metadata (`GET /api/v1/links/{code}`). |
| FR-4 | Soft-delete / disable a link (`DELETE /api/v1/links/{code}`). |
| FR-5 | Analytics: total clicks, unique visitors, clicks per day, top referrers, user-agent family (`GET /api/v1/links/{code}/stats`). |
| FR-6 | Idempotent create via `Idempotency-Key` header. |
| NFR-1 | Redirect p99 < 50 ms at the service on a cache hit (local measurement). |
| NFR-2 | Reliability: degrades gracefully if Redis is down (falls back to the DB). Analytics never blocks redirects. |
| NFR-3 | Security: URL validation (scheme allowlist, SSRF/private-IP block, max length), API-key auth on management endpoints, rate limiting, no raw IPs stored. |
| NFR-4 | Observability: health, Prometheus metrics, structured JSON logs with a correlation ID. |
| NFR-5 | Codes are not enumerable (not sequential), with no collisions. |
| NFR-6 | Quality: ≥ 80 % line coverage on domain/service code. Unit and integration tests. OpenAPI spec. |

### 1.2 Platform: Agentic SDLC Orchestrator (the *critical differentiator*)

| ID | Requirement (from brief §4) |
|---|---|
| OR-1 | **Requirement understanding**: interpret intent, detect ambiguity, emit a normalized spec plus clarifying questions. |
| OR-2 | **Task decomposition**: tasks with dependencies and sequencing (a task DAG). |
| OR-3 | **Codebase reasoning (brownfield)**: impacted modules, APIs, data flows, files. |
| OR-4 | **Explicit dependency graph** of SDLC stages with **entry/exit gates**. |
| OR-5 | **Sequential + parallel** paths with **synchronization (join)**. |
| OR-6 | **Cross-stage context** and **decision lineage** (every artifact knows its inputs). |
| OR-7 | **Human approval checkpoints** for high-impact actions. |
| OR-8 | **Bounded retries, fallback, rollback, safe-stop**. |
| OR-9 | **Policy guardrails**: security, compliance, change control. |
| OR-10 | **Audit-grade observability and traceability** (tamper-evident). |
| OR-11 | **Reliability metrics**: success rate, retry and rollback frequency, MTTR, end-to-end latency. |
| OR-12 | **Dynamic re-planning** when upstream outputs change, while governance still applies. |
| OR-13 | **Engineering output generation**: code, API/schema, tests, docs. |
| OR-14 | **Validation and risk control**: deterministic verification, risk register. |
| OR-15 | **Controlled autonomy**: autonomy levels. Humans approve and own final quality. |
| OR-16 | **Final engineering summary** generated per run. |

### 1.3 Deliverables

| ID | Deliverable |
|---|---|
| D-1 | A working prototype that runs end-to-end (`docker compose up` plus one command). |
| D-2 | Architecture overview (`docs/architecture.md`). |
| D-3 | Three scenarios (greenfield, brownfield, ambiguous). Each shows decomposition, orchestration and validation. |
| D-4 | Setup instructions (`docs/setup.md`). |
| D-5 | Testing approach, limitations and trade-offs (`docs/testing.md`, `docs/final-summary.md`). |

### 1.4 Assumptions (record them, don't hide them)

- A-1 Single region. Single-node demo; the design must allow horizontal scaling.
- A-2 The LLM is optional at runtime. **Replay mode** gives deterministic demos and tests without an API key.
- A-3 Approvers are identified by an API key with role `APPROVER`. No SSO in the prototype.
- A-4 Analytics delivery is *at-most-once*. Losing a small number of clicks under overload is acceptable. Slowing redirects is not.
- A-5 "Publish" means a human-approved merge of the agent branch into `main` locally. No real CI/CD deploy.

---

## 2. Architecture at a Glance

```
┌────────────────────────────── orchestrator-app (Spring Boot :8081) ──────────────────────────────┐
│ REST API  ·  Dashboard (static HTML)  ·  Approvals  ·  Metrics  ·  Audit verify                   │
│        │                                                                                          │
│ ┌──────▼──────────── orchestrator-core (pure Java, no Spring) ────────────────────────────────┐  │
│ │ WorkflowDefinition (YAML) → DAG ─► Coordinator (single-thread event loop, state machine)     │  │
│ │   ├─ Scheduler (Kahn readiness, parallel dispatch on virtual threads, joins)                 │  │
│ │   ├─ GateEvaluator (entry/exit)   ├─ PolicyEngine (ALLOW / REQUIRE_APPROVAL / DENY)          │  │
│ │   ├─ RetryPolicy / Fallback       ├─ CheckpointManager (git) / Rollback                      │  │
│ │   ├─ Replanner (downstream invalidation, dynamic task expansion)                             │  │
│ │   ├─ EventStore (append-only, SHA-256 hash chain)  ├─ ArtifactStore (versioned + lineage)    │  │
│ │   └─ Budget / KillSwitch (safe-stop)               └─ MetricsRecorder                        │  │
│ └──────────────────────────────────────────────────────────────────────────────────────────────┘  │
│ ┌──────────── orchestrator-agents ────────────┐   ┌── Tools (sandboxed) ──┐                      │
│ │ Requirements · Decomposition · Impact ·     │──►│ JGit worktree, file   │──► target repo:      │
│ │ Design · Implementation · TestAuthoring ·   │   │ R/W (path-jailed),    │    shortener-service │
│ │ Docs · Validation(deterministic) · Release  │   │ mvn runner (allowlist)│    on branch         │
│ │ LlmGateway: live | record | replay          │   └───────────────────────┘    agent/<runId>     │
│ └─────────────────────────────────────────────┘                                                  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────── shortener-service (Spring Boot :8080) ────────────┐
│ Controllers → LinkService → CodeGenerator(Feistel+Base62)     │
│ Cache: Caffeine L1 → Redis L2 → Postgres                      │
│ RateLimiter: Redis token bucket (Lua) w/ local fallback       │
│ Analytics: bounded queue → batch writer → click_event,        │
│            click_daily, Redis HyperLogLog                     │
└───────────────────────────────────────────────────────────────┘
Infra: PostgreSQL 16 · Redis 7 · (optional) Prometheus + Grafana — via docker-compose
```

**SDLC stage DAG (default workflow `sdlc-standard`):**

```
requirements ──► decomposition ──► impact_analysis* ──► design ─┬─► implementation[task fan-out] ─┐
  [🧑‍⚖️ spec]                         (*brownfield only)  [🧑‍⚖️]   ├─► test_authoring ─────────────────┼─► validation ─┐
                                                                └─► docs_draft ───────────┐       │   (loop back ≤2│
                                                                                          │       └───── to impl) │
                                                                                          └──► docs_finalize ◄────┘
                                                                                                  │
                                                                                          release_readiness [🧑‍⚖️]
                                                                                                  │
                                                                                               publish [🧑‍⚖️ high-impact]
```

---

## 3. Tech Stack (short form; see the rationale doc §2)

| Concern | Choice |
|---|---|
| Language | Java 21 LTS (records, sealed interfaces, virtual threads). Java 25 also works. |
| Framework | Spring Boot, latest stable (generate it from start.spring.io; don't guess the version) |
| Build | Maven multi-module, Maven Wrapper |
| DB | PostgreSQL 16 + Flyway |
| Cache / limiter / HLL | Redis 7 (Lettuce client) + Caffeine (L1) |
| Resilience | Resilience4j (circuit breaker, timeouts) |
| API docs | springdoc-openapi (OpenAPI 3) |
| LLM | Official Anthropic Java SDK (`com.anthropic:anthropic-java`) behind a `LlmGateway` port; record/replay provider |
| Git ops | JGit (no shelling out to git) |
| Schema validation of agent outputs | networknt `json-schema-validator` |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers, jqwik (property-based), ArchUnit, JaCoCo |
| Quality | Spotless (google-java-format), Checkstyle-lite, SpotBugs (optional), OWASP dependency-check (optional) |
| Observability | Micrometer + Prometheus, logstash-logback-encoder (JSON logs), MDC correlation IDs |

---

## 4. Repository Layout (target)

```
agentic-url-shortener/
├── CLAUDE.md                         # rules for Claude Code (Step 0.2)
├── pom.xml                           # parent POM, dependency management
├── mvnw, .mvn/
├── docker-compose.yml                # postgres, redis, (optional) prometheus, grafana
├── .env.example
├── config/
│   ├── workflows/sdlc-standard.yml   # stage DAG, gates, retries, approvals
│   ├── policies.yml                  # guardrail rules
│   └── autonomy.yml                  # autonomy levels → which actions need approval
├── shortener-service/                # TARGET SYSTEM
│   └── src/main/java/com/acme/shortener/{api,domain,service,infra,config}
├── orchestrator-core/                # pure-Java engine (no Spring)
│   └── src/main/java/com/acme/orchestrator/core/{model,dag,engine,gate,policy,retry,checkpoint,replan,event,artifact,metrics,budget}
├── orchestrator-agents/              # agents + LLM gateway + tools
│   └── src/main/java/com/acme/orchestrator/agents/{llm,tools,requirements,decomposition,impact,design,implementation,testing,docs,validation,release}
│   └── src/main/resources/prompts/*.md, schemas/*.json
├── orchestrator-app/                 # Spring Boot wiring, REST, persistence, dashboard
│   └── src/main/resources/static/index.html
├── scenarios/
│   ├── greenfield-qr-code/{requirement.md, replay/}
│   ├── brownfield-link-expiry/{requirement.md, replay/}
│   └── ambiguous-abuse-protection/{requirement.md, answers.json, replay/}
├── scripts/{demo.sh, run-scenario.sh, verify-audit.sh}
└── docs/
    ├── plan/BUILD_PLAN.md, plan/DESIGN_RATIONALE.md
    ├── architecture.md, setup.md, testing.md, final-summary.md
    ├── adr/0001-*.md …
    └── scenarios/{greenfield,brownfield,ambiguous}.md
```

---

## 5. Prerequisites and Setup

| Tool | Version | Check |
|---|---|---|
| JDK | 21+ (Temurin) | `java -version` |
| Docker Desktop / Engine | recent, with Compose v2 | `docker compose version` |
| Git | 2.40+ | `git --version` |
| IntelliJ IDEA | recent, with the Claude Code plugin | — |
| (optional) Anthropic API key | — | `ANTHROPIC_API_KEY` env var, needed only for `live`/`record` mode |
| (optional) `jq`, `curl` | — | used by the demo scripts |

IntelliJ settings: Project SDK = 21. Turn on annotation processing. Enable "Build and run using Maven". Install the google-java-format plugin, or rely on Spotless.

---

## 6. Configuration Reference

The rationale doc §6 explains each value.

### 6.1 `.env.example`
```dotenv
POSTGRES_USER=app
POSTGRES_PASSWORD=change-me
POSTGRES_DB=shortener
REDIS_URL=redis://localhost:6379
SHORTENER_BASE_URL=http://localhost:8080
SHORTENER_CODE_SECRET=change-me-32-bytes-min          # Feistel key; rotate = new code space
SHORTENER_API_KEYS=dev:8f1c...sha256hash              # name:sha256(key)
VISITOR_HASH_SALT_SECRET=change-me
ORCH_LLM_MODE=replay                                  # live | record | replay
ANTHROPIC_API_KEY=                                    # only for live/record
ORCH_LLM_MODEL=claude-sonnet-5-5
ORCH_APPROVER_KEYS=alice:sha256hash
ORCH_WORKSPACE_ROOT=../.agent-worktrees
```

### 6.2 `shortener-service/src/main/resources/application.yml` (key parts)
```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/${POSTGRES_DB}
    hikari: { maximum-pool-size: 20, connection-timeout: 2000 }
  flyway: { enabled: true }
  data.redis: { url: ${REDIS_URL}, timeout: 200ms }
  threads.virtual.enabled: true
shortener:
  base-url: ${SHORTENER_BASE_URL}
  code: { length: 7, secret: ${SHORTENER_CODE_SECRET}, feistel-rounds: 4 }
  alias: { min: 4, max: 32, pattern: "^[A-Za-z0-9_-]+$", reserved: [api, actuator, admin, health, swagger-ui] }
  url: { max-length: 2048, allowed-schemes: [http, https], block-private-networks: true }
  cache: { l1-max-size: 10000, l1-ttl: 5m, l2-ttl: 24h, negative-ttl: 60s }
  rate-limit:
    create: { capacity: 60, refill-per-minute: 60 }      # per API key
    redirect: { capacity: 600, refill-per-minute: 600 }  # per client fingerprint
  analytics: { queue-capacity: 10000, batch-size: 500, flush-interval: 1s }
  redirect: { status: 302 }
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true
resilience4j.circuitbreaker.instances.redis:
  sliding-window-size: 20
  failure-rate-threshold: 50
  wait-duration-in-open-state: 10s
```

### 6.3 `config/workflows/sdlc-standard.yml`
```yaml
workflow: sdlc-standard
version: 1
budgets: { maxWallClock: 30m, maxLlmCalls: 60, maxTokens: 400000 }
defaults: { retry: { maxAttempts: 2, backoff: exponential, baseDelay: 2s, maxDelay: 20s, jitter: true }, timeout: 5m }
stages:
  - id: requirements
    agent: RequirementsAgent
    entryGates: [input.nonEmpty]
    exitGates: [artifact.schemaValid:requirements_spec, spec.noBlockingQuestions]
    approval: { required: true, reason: "Scope sign-off" }
  - id: decomposition
    agent: DecompositionAgent
    dependsOn: [requirements]
    exitGates: [artifact.schemaValid:task_graph, taskGraph.acyclic, taskGraph.everyRequirementCovered]
  - id: impact_analysis
    agent: ImpactAnalysisAgent
    dependsOn: [decomposition]
    condition: "run.mode == 'BROWNFIELD'"          # otherwise SKIPPED (counts as satisfied)
    exitGates: [artifact.schemaValid:impact_report]
  - id: design
    agent: DesignAgent
    dependsOn: [decomposition, impact_analysis]
    exitGates: [artifact.schemaValid:design_doc, design.apiContractPresent]
    approval: { required: true, reason: "Architecture sign-off" }
  - id: implementation
    agent: ImplementationAgent
    dependsOn: [design]
    expand: tasks                                   # dynamic fan-out per task in task_graph (respects task deps)
    checkpoint: git
    retry: { maxAttempts: 3 }
    fallback: { strategy: minimal-patch }           # smaller-scope prompt, then template
  - id: test_authoring
    agent: TestAuthoringAgent
    dependsOn: [design]                             # parallel with implementation (spec-first tests)
    checkpoint: git
  - id: docs_draft
    agent: DocsAgent
    dependsOn: [design]                             # parallel
  - id: validation
    agent: ValidationAgent                          # deterministic: build, tests, coverage, policy scan
    dependsOn: [implementation, test_authoring]     # JOIN
    exitGates: [build.green, tests.green, coverage.min:0.80, policy.noDeny]
    onFailure: { loopTo: implementation, maxLoops: 2, feedback: validation_report }
  - id: docs_finalize
    agent: DocsAgent
    dependsOn: [validation, docs_draft]             # JOIN
  - id: release_readiness
    agent: ReleaseAgent
    dependsOn: [docs_finalize]
    exitGates: [release.checklistComplete]
    approval: { required: true, reason: "Go / no-go" }
  - id: publish
    action: git.mergeToMain
    dependsOn: [release_readiness]
    highImpact: true
    approval: { required: true, reason: "Merge to main" }
```

### 6.4 `config/policies.yml`
```yaml
policies:
  - id: SEC-001-no-secrets
    on: diff
    deny_patterns: ["AKIA[0-9A-Z]{16}", "-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY-----", "(?i)(password|secret|api[_-]?key)\\s*[:=]\\s*['\"][^'\"]{8,}"]
    effect: DENY
  - id: SEC-002-path-jail
    on: file_write
    allow_paths: ["shortener-service/src/**", "docs/**", "scenarios/**"]
    effect_outside: DENY
  - id: SEC-003-forbidden-paths
    on: file_write
    paths: [".github/**", "config/policies.yml", "config/autonomy.yml", "orchestrator-*/**"]   # agents can't change their own guardrails
    effect: DENY
  - id: CHG-001-schema-migration
    on: file_write
    paths: ["**/db/migration/**"]
    effect: REQUIRE_APPROVAL
  - id: CHG-002-dependency-change
    on: file_write
    paths: ["**/pom.xml"]
    effect: REQUIRE_APPROVAL
  - id: CHG-003-blast-radius
    on: diff
    max_files_changed: 25
    max_lines_changed: 1200
    effect: REQUIRE_APPROVAL
  - id: CHG-004-security-config
    on: file_write
    paths: ["**/config/Security*.java", "**/application*.yml"]
    effect: REQUIRE_APPROVAL
  - id: CMP-001-pii
    on: diff
    deny_patterns: ["(?i)log\\.(info|debug|warn)\\(.*(remoteAddr|getRemoteAddr|X-Forwarded-For)"]
    effect: DENY
    message: "Raw client IPs must not be logged (privacy)."
  - id: CMD-001-command-allowlist
    on: command
    allow: ["mvn -q -pl shortener-service -am verify", "mvn -q -pl shortener-service test"]
    effect_outside: DENY
```

### 6.5 `config/autonomy.yml`
```yaml
levels:
  L0_SUGGEST:   { description: "Agents produce artifacts only; every write needs approval" }
  L1_SUPERVISED:{ description: "Writes inside path jail auto-allowed; policy REQUIRE_APPROVAL + stage approvals enforced" }   # default
  L2_TRUSTED:   { description: "Like L1 but design approval skipped for risk score < 30; publish ALWAYS needs approval" }
invariants:      # cannot be relaxed by any level
  - publish requires human approval
  - DENY policies are never overridable
  - approvals must come from a human principal with role APPROVER
```

---

## 7. Phased Build Steps

> Each step has: **Goal → Prompt for Claude Code → ✅ Acceptance → Commit**.
> Requirement IDs are in brackets.

---

### PHASE 0 — Foundations (Day 1, ~1.5 h)

#### Step 0.1 — Scaffold the multi-module project
**Prompt:**
```
Create a Maven multi-module project "agentic-url-shortener" (groupId com.acme) following
docs/plan/BUILD_PLAN.md §4. Modules: shortener-service, orchestrator-core, orchestrator-agents,
orchestrator-app. Use Java 21, the Maven Wrapper, and the latest stable Spring Boot (check
start.spring.io conventions; put the BOM in the parent's dependencyManagement). orchestrator-core must
have NO Spring dependency. Add Spotless (google-java-format) and JaCoCo to the parent.
Add a minimal Application class + health endpoint in shortener-service (8080) and orchestrator-app (8081).
Add .gitignore (target/, .idea/, .env, .agent-worktrees/).
```
✅ `./mvnw -q verify` passes. Both apps start and `/actuator/health` returns UP.
Commit: `chore: scaffold multi-module project`

#### Step 0.2 — CLAUDE.md (rules for the coding agent)
**Prompt:**
```
Create CLAUDE.md at repo root with: project purpose (1 paragraph), module map, build/test commands,
coding standards (records for DTOs, constructor injection, no field injection, no Lombok, Problem Details
for errors, package-by-feature), testing rules (every public service method has unit tests; integration
tests use Testcontainers; no sleeping in tests — use Awaitility), safety rules (never commit secrets,
never edit config/policies.yml without explicit instruction, run ./mvnw verify before declaring a step
done), and "Always read docs/plan/BUILD_PLAN.md step before working; do only that step."
```
Commit: `docs: add CLAUDE.md agent rules`

#### Step 0.3 — Local infrastructure
**Prompt:**
```
Create docker-compose.yml with postgres:16 (healthcheck, volume, init script creating databases
'shortener' and 'orchestrator'), redis:7 (appendonly yes, healthcheck), and an optional "observability"
profile with prometheus (scraping 8080 and 8081 /actuator/prometheus) and grafana. Create .env.example
from BUILD_PLAN §6.1. Add scripts/dev-up.sh and scripts/dev-down.sh.
```
✅ `docker compose up -d` → both containers healthy.
Commit: `chore: local infra via docker compose` · 🏷️ `phase-0`

---

### PHASE 1 — URL Shortener Core (Day 1, ~3 h) [FR-1..4, FR-6, NFR-3, NFR-5]

#### Step 1.1 — Schema and domain
**Prompt:**
```
In shortener-service add Flyway migration V1__init.sql:
- sequence link_id_seq
- table link(id bigint pk, code varchar(32) unique not null, original_url text not null,
  custom_alias boolean not null default false, status varchar(16) not null default 'ACTIVE'
  check (status in ('ACTIVE','DELETED')), created_by varchar(64), created_at timestamptz default now(),
  updated_at timestamptz default now(), version int not null default 0)
- table idempotency_record(key varchar(128) pk, api_key_name varchar(64), request_hash char(64),
  response_status int, response_body jsonb, created_at timestamptz default now())
- index on idempotency_record(created_at)
Domain: Link record/entity, LinkStatus enum, LinkRepository (Spring Data JDBC — not JPA).
```
Commit: `feat(shortener): schema and domain model`

#### Step 1.2 — Code generation (DSA: Feistel permutation + Base62)
**Prompt:**
```
Implement com.acme.shortener.domain.code:
- Base62 (alphabet 0-9a-zA-Z): encode(long, fixedLength) / decode(String). Left-pad to fixed length.
- FeistelPermutation: keyed, balanced Feistel network over 42 bits (two 21-bit halves), N rounds
  (config), round function = first 21 bits of HMAC-SHA256(secret, round||half). Use cycle-walking so the
  output is always < 62^7. It must be a bijection on [0, 62^7).
- CodeGenerator: id = nextval('link_id_seq') → permute → Base62(7).
- AliasValidator: length, pattern, reserved words, case-insensitive uniqueness check.
Tests: jqwik property tests — decode(encode(x)) == x; permute is a bijection (inverse(permute(x)) == x);
permute(x) < 62^7; plus a sample test showing 1000 consecutive ids produce non-sequential codes with
no collisions.
```
✅ Property tests pass (≥ 10k tries).
Commit: `feat(shortener): collision-free non-enumerable code generation`

#### Step 1.3 — URL validation and SSRF guard
**Prompt:**
```
Implement UrlValidator: parse with java.net.URI; require scheme in allowed-schemes; require host;
max length; reject userinfo (user:pass@); reject hosts that are IP literals or resolve to
loopback/link-local/site-local/multicast/unique-local (IPv4+IPv6) when block-private-networks=true;
reject our own base-url host (no redirect loops). DNS resolution must have a 500ms timeout and be
mockable via a HostResolver interface. Unit tests for each rule incl. "http://127.0.0.1",
"http://[::1]", "javascript:alert(1)", "http://user:pw@x.com", 2049-char URL.
```
Commit: `feat(shortener): URL validation with SSRF protection`

#### Step 1.4 — Link service and REST API
**Prompt:**
```
Implement LinkService (create, resolve, get, delete) and controllers:
- POST /api/v1/links  body {url, customAlias?}  → 201 {code, shortUrl, originalUrl, createdAt}
  Header Idempotency-Key (optional): same key+same body → replay stored response; same key+different
  body → 422. Store records 24h (scheduled cleanup).
- GET /{code} → 302 Location, Cache-Control: private, max-age=0. Unknown/deleted → 404 Problem Details.
- GET /api/v1/links/{code} → metadata.
- DELETE /api/v1/links/{code} → 204, soft delete (status=DELETED).
Management endpoints require X-API-Key (compare SHA-256 hash, constant-time). Errors use RFC 9457
ProblemDetail with a stable "type" URI per error. Add springdoc-openapi; export openapi.yaml to
shortener-service/src/main/resources/static/openapi.yaml at build time or via a test.
Unit tests for LinkService (mock repo); MockMvc slice tests for controllers.
```
✅ `curl` create → redirect works. Swagger UI is at `/swagger-ui.html`.
Commit: `feat(shortener): core link APIs` · 🧑‍⚖️ Review the API contract before you go on.

---

### PHASE 2 — Reliability, Performance, Analytics (Day 1, ~3 h) [FR-5, NFR-1..4]

#### Step 2.1 — Two-level cache with negative caching
**Prompt:**
```
Add a LinkCache port with implementation: Caffeine L1 (max size, TTL) → Redis L2 (TTL) → DB.
Negative-cache "not found" for negative-ttl. Wrap Redis calls in a Resilience4j circuit breaker
"redis" with 200ms timeout; on open/failure fall through to DB (log WARN once per state change, metric
cache.redis.fallback). resolve(code) uses the cache; create() populates it.
IMPORTANT (intentional seeded defect for the brownfield scenario): delete() must NOT evict the cache.
Add a comment "// KNOWN-ISSUE BF-1: cache not invalidated on delete" and list it in docs/known-issues.md.
Tests: unit tests with in-memory fakes; Testcontainers integration test for Redis path and for
Redis-down fallback (stop container mid-test).
```
⚠️ The seeded bug is deliberate. Don't let Claude Code "helpfully" fix it.
Commit: `feat(shortener): L1/L2 cache with graceful degradation`

#### Step 2.2 — Rate limiting (DSA: token bucket)
**Prompt:**
```
Implement RateLimiter port:
- RedisTokenBucketRateLimiter using an atomic Lua script (HMGET tokens,ts → refill by elapsed*rate →
  consume → HMSET + PEXPIRE). Key: rl:{scope}:{subject}.
- InMemoryTokenBucketRateLimiter (ConcurrentHashMap<String, Bucket> with synchronized bucket; bounded
  via Caffeine to avoid memory leak) used as fallback when Redis circuit is open.
- Policy: create endpoints keyed by API key name; redirect keyed by visitor fingerprint
  (sha256(ip + ua + daily salt)). Redirect FAILS OPEN on limiter error; create uses local fallback.
- Respond 429 with Retry-After and RateLimit-* headers.
Tests: deterministic Clock injected; burst then refill; concurrency test (100 threads) never exceeds
capacity.
```
Commit: `feat(shortener): token-bucket rate limiting`

#### Step 2.3 — Async analytics pipeline (DSA: bounded queue, batching, HyperLogLog)
**Prompt:**
```
Migration V2__analytics.sql: click_event(id bigserial, link_id bigint, occurred_at timestamptz,
referrer_host varchar(255), ua_family varchar(32), visitor_hash char(64)) + index (link_id, occurred_at);
click_daily(link_id bigint, day date, clicks bigint, primary key(link_id, day)).
Implement:
- ClickEventPublisher: non-blocking offer() into ArrayBlockingQueue(capacity); if full → drop and
  increment analytics.events.dropped.
- ClickBatchWriter: single background (virtual) thread drains up to batch-size or every flush-interval;
  JDBC batch insert click_event; upsert click_daily (ON CONFLICT DO UPDATE clicks = clicks + EXCLUDED);
  Redis PFADD hll:{linkId}:{yyyyMMdd} visitor_hash (EXPIRE 400d). Flush on graceful shutdown.
- UA family parsing (simple regex: Chrome/Firefox/Safari/Edge/Bot/Other); referrer → host only.
- GET /api/v1/links/{code}/stats?from=&to= → {totalClicks, uniqueVisitors (PFCOUNT over day keys),
  clicksByDay[], topReferrers[5], byUaFamily{}}. Range max 90 days.
Never store raw IP. Redirect path must not wait on analytics.
Tests: publisher drop behaviour; writer batching with Awaitility; Testcontainers end-to-end
"100 redirects → stats show 100 clicks".
```
Commit: `feat(shortener): async click analytics with HLL uniques`

#### Step 2.4 — Observability and hardening
**Prompt:**
```
Add: JSON logging (logstash-logback-encoder) with MDC correlationId (from X-Correlation-Id or
generated, echoed in response); Micrometer timers for redirect/create, counters for cache hit/miss per
level, rate-limit rejections, analytics queue depth gauge. Health groups: liveness (app), readiness
(db required; redis reported but NOT required → degraded). Security headers. Graceful shutdown.
Add a JaCoCo rule: fail build under 80% line coverage for com.acme.shortener.{domain,service}.
```
✅ `./mvnw verify` green. Coverage gate is enforced.
Commit: `feat(shortener): observability and coverage gate` · 🏷️ `baseline-v1` (the brownfield starting point)

#### Step 2.5 — Shortener docs
**Prompt:**
```
Write shortener-service/README.md (APIs with curl examples, config table) and docs/known-issues.md
(BF-1). Add docs/adr/0001-code-generation.md, 0002-redirect-302.md, 0003-cache-strategy.md,
0004-analytics-at-most-once.md using the reasoning in docs/plan/DESIGN_RATIONALE.md.
```
Commit: `docs(shortener): README and ADRs` · 🧑‍⚖️ End of Day 1 review.

---

### PHASE 3 — Orchestrator Core Engine (Day 2, ~3 h) [OR-4, OR-5, OR-6, OR-10]

#### Step 3.1 — Core model
**Prompt:**
```
In orchestrator-core (pure Java, no Spring) create package model with records/sealed types:
- WorkflowDefinition, StageDefinition(id, agentName|action, dependsOn, condition, entryGates,
  exitGates, approval, retry, fallback, checkpoint, onFailure, expand, highImpact, timeout).
- Run(id, workflowId, mode GREENFIELD|BROWNFIELD|AUTO, autonomyLevel, status, budgets).
- StageInstance(runId, stageId, taskId?, status, attempt, loopCount, startedAt, endedAt).
- StageStatus sealed/enum: PENDING, READY, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, RETRYING,
  SKIPPED, INVALIDATED, ROLLED_BACK, HALTED — with an explicit transition table
  (StageStateMachine.transition(from, event) → to or IllegalTransitionException).
- RunStatus: CREATED, RUNNING, AWAITING_HUMAN, COMPLETED, FAILED, HALTED, ROLLED_BACK.
- Artifact(id, runId, stageId, type, version, contentJson, contentHash, producedBy(agent, model,
  promptHash), inputs: List<ArtifactRef>, rationale, createdAt).
- WorkflowLoader: parse YAML (SnakeYAML/Jackson YAML) into WorkflowDefinition + validation.
Tests: state machine — every legal and illegal transition.
```
Commit: `feat(orch-core): workflow, run, stage and artifact model`

#### Step 3.2 — DAG (DSA: Kahn's algorithm, readiness, reachability)
**Prompt:**
```
Implement dag.Dag<T>: adjacency list + reverse adjacency; addNode/addEdge; topologicalOrder() via
Kahn's algorithm with cycle detection (throw CycleDetectedException listing the cycle path found by DFS);
levels() for parallel layers; readyNodes(statusMap) = nodes whose deps are all SUCCEEDED or SKIPPED;
downstreamClosure(node) via BFS (for re-plan invalidation); upstreamClosure(node) (for lineage).
Dynamic expansion: expand(stageId, List<TaskNode> with internal deps) replaces node with subgraph whose
roots inherit stage deps and whose sinks feed stage dependents (validate acyclic after expansion).
Tests: cycle detection, diamond join, levels, expansion preserves ordering, closure correctness;
jqwik property: topological order respects every edge for random DAGs.
```
Commit: `feat(orch-core): DAG with topo sort, closures, dynamic expansion`

#### Step 3.3 — Event store (audit, hash chain) and artifact store
**Prompt:**
```
Define ports EventStore and ArtifactStore (+ in-memory impls for tests).
Event(seq, runId, type, stageId?, actor (agent:<name> | human:<id> | system), payload map,
timestamp, prevHash, hash) where hash = SHA-256(prevHash + canonicalJson(event-without-hash)).
Event types: RUN_CREATED, STAGE_READY, STAGE_STARTED, AGENT_INVOKED, ARTIFACT_PRODUCED,
GATE_EVALUATED, POLICY_EVALUATED, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED,
STAGE_SUCCEEDED, STAGE_FAILED, STAGE_RETRIED, FALLBACK_USED, CHECKPOINT_CREATED, ROLLED_BACK,
REPLAN_TRIGGERED, STAGE_INVALIDATED, STAGE_SKIPPED, BUDGET_EXCEEDED, RUN_HALTED, RUN_RESUMED,
RUN_COMPLETED, RUN_FAILED.
AuditVerifier.verify(runId) recomputes the chain and reports first broken seq.
RunStateProjector rebuilds Run + StageInstances purely from events (event sourcing) — used for
crash recovery.
ArtifactStore: versioned (same type+stage → version+1), lineage query returns ancestor graph.
Tests: tampering a payload is detected; projector reproduces state.
```
Commit: `feat(orch-core): hash-chained event store, projector, artifact lineage`

#### Step 3.4 — Coordinator and scheduler
**Prompt:**
```
Implement engine.Coordinator: a single-threaded event loop (one dedicated thread consuming a
BlockingQueue<Command>) that owns ALL state transitions; agents run on a virtual-thread executor and
report back via Commands (StageCompleted, StageFailed, ApprovalDecision, AmendRequirement, Halt, Resume).
Loop: on each command → append events → recompute readyNodes → evaluate condition (SKIP if false) →
evaluate entry gates → dispatch READY stages in parallel → on completion evaluate exit gates → mark
SUCCEEDED or FAILED. Joins are natural: a node is READY only when all deps are SUCCEEDED/SKIPPED.
Define port Agent { AgentResult execute(StageContext ctx) } where StageContext gives read access to
upstream artifacts (by type, latest version), run metadata, prior attempt feedback, and a
ToolRegistry; agents NEVER mutate run state directly.
Tests with fake agents: linear chain, parallel fan-out + join (assert overlap in time), conditional skip,
deterministic ordering of events.
```
✅ The test proves `implementation`, `test_authoring` and `docs_draft` run at the same time and `validation` waits for its joins.
Commit: `feat(orch-core): coordinator event loop and parallel scheduler` · 🏷️ `phase-3`

---

### PHASE 4 — Governance and Resilience Controls (Day 2, ~2.5 h) [OR-7, OR-8, OR-9, OR-12, OR-15]

#### Step 4.1 — Gates
**Prompt:**
```
Implement gate.GateEvaluator with a registry of named gates (string id → Gate). Gates from
sdlc-standard.yml: input.nonEmpty, artifact.schemaValid:<type>, spec.noBlockingQuestions,
taskGraph.acyclic, taskGraph.everyRequirementCovered, design.apiContractPresent, build.green,
tests.green, coverage.min:<x>, policy.noDeny, release.checklistComplete. Each returns
GateResult(pass, reasons). Every evaluation emits GATE_EVALUATED. Unknown gate id = workflow load error.
```
Commit: `feat(orch-core): entry/exit gates`

#### Step 4.2 — Policy engine and autonomy levels
**Prompt:**
```
Implement policy.PolicyEngine loading config/policies.yml and config/autonomy.yml.
evaluate(ProposedAction) → Decision {ALLOW | REQUIRE_APPROVAL | DENY, matchedPolicies, riskScore}.
ProposedAction types: FileWrite(path, content), Diff(unifiedDiff, filesChanged, linesChanged),
Command(cmdline), Publish. Risk score 0-100 (sum of weights per matched rule + size factor).
Combine: any DENY → DENY; else any REQUIRE_APPROVAL → REQUIRE_APPROVAL; autonomy level may only
make things STRICTER, except L2 design-approval skip for risk<30. Invariants from autonomy.yml
enforced in code (not configurable). Policy files are loaded once at run start and their hash
recorded in RUN_CREATED (change control: a run can't pick up mid-run policy edits).
Tests: one per policy in policies.yml + invariant tests (publish always needs approval at L2).
```
Commit: `feat(orch-core): policy guardrails and autonomy levels`

#### Step 4.3 — Human approvals
**Prompt:**
```
Implement approval flow: when a stage with approval.required finishes its work (or a policy returns
REQUIRE_APPROVAL for an action), stage → WAITING_APPROVAL, run → AWAITING_HUMAN, emit
APPROVAL_REQUESTED with a summary (artifact refs, diff stats, risk score, matched policies).
ApprovalDecision(approverId, role, decision APPROVE|REJECT, comment). Validate role == APPROVER and
actor is human (agent principals rejected). APPROVE → continue. REJECT → stage is re-run with the
comment injected as feedback (counts toward retry budget) and downstream is invalidated (see 4.5).
Approval timeout (config, default none) → HALT.
```
Commit: `feat(orch-core): human approval checkpoints`

#### Step 4.4 — Retries, fallback, checkpoints, rollback, safe-stop
**Prompt:**
```
- retry.RetryPolicy: maxAttempts, exponential backoff with full jitter, capped; classify failures
  (Transient: LLM timeout/429/5xx, Validation: schema/gate failure → retry with feedback,
  Fatal: policy DENY / illegal state → no retry).
- Fallback: after retries exhausted, if stage.fallback defined → invoke agent with fallback strategy
  (emit FALLBACK_USED); if that fails → stage FAILED → run FAILED (or HALT if highImpact).
- validation.onFailure.loopTo: re-schedule target stage with feedback artifact, bounded by maxLoops;
  then FAILED.
- checkpoint.CheckpointManager port: create(runId, stageId) → ref; restore(ref). Git impl comes in
  Phase 5 (JGit: commit on agent branch, tag-like ref refs/orch/<runId>/<stageId>).
- Rollback: rollbackTo(stageId) restores checkpoint taken BEFORE that stage, marks that stage and its
  downstream closure ROLLED_BACK→PENDING, emits ROLLED_BACK. Also automatic rollback when a stage fails
  after partially writing files.
- budget.BudgetGuard: wall clock, LLM calls, tokens; exceeded → BUDGET_EXCEEDED → safe-stop.
- Safe-stop (Halt command / kill switch / budget / repeated policy DENY ≥3): stop dispatching, let
  RUNNING stages finish or cancel after grace period, checkpoint, run → HALTED. Resume command rebuilds
  from events and continues.
Tests: each control with fake agents and fake checkpoint manager; MTTR timestamps present on events.
```
Commit: `feat(orch-core): retries, fallback, rollback, safe-stop, budgets`

#### Step 4.5 — Dynamic re-planning
**Prompt:**
```
Implement replan.Replanner. Triggers:
 (a) AmendRequirement(newText) on a running run → new requirements_spec version;
 (b) approval REJECT with feedback;
 (c) upstream artifact content hash changes on re-run (e.g., design v2 ≠ v1);
 (d) decomposition output changes task set → re-expand implementation subgraph (diff old vs new tasks:
     keep SUCCEEDED tasks whose inputs are unchanged, invalidate changed/removed, add new).
Algorithm: changed node → dag.downstreamClosure → mark INVALIDATED (emit STAGE_INVALIDATED with
reason+lineage) → rollback git to the earliest invalidated stage's checkpoint → PENDING → scheduler
picks up. Approvals on invalidated stages are VOIDED (must be re-approved — governance preserved).
Emit REPLAN_TRIGGERED with before/after artifact hashes.
Tests: amending requirements after design approval invalidates design..publish, voids approval,
keeps requirements history (v1, v2) in lineage.
```
✅ The re-plan test passes and the audit chain still verifies.
Commit: `feat(orch-core): dynamic re-planning with governance` · 🏷️ `phase-4` · 🧑‍⚖️ Review the engine before you add LLMs.

---

### PHASE 5 — Agents, LLM Gateway, Tools (Day 2, ~3 h) [OR-1, OR-2, OR-3, OR-13, OR-14]

#### Step 5.1 — LLM gateway (live / record / replay)
**Prompt:**
```
In orchestrator-agents create llm.LlmGateway port: complete(LlmRequest{agent, systemPrompt,
userPrompt, jsonSchema, maxTokens, temperature}) → LlmResponse{text, json, inputTokens, outputTokens,
model, latency}.
Implementations:
- AnthropicLlmGateway using the official Anthropic Java SDK; model from config; timeout 60s;
  Resilience4j circuit breaker + retry on 429/5xx; fallback model configurable.
- RecordingLlmGateway decorator: writes request-hash → response JSON to scenarios/<id>/replay/.
- ReplayLlmGateway: looks up by request hash (sha256 of agent+prompts+schema); missing → clear error
  telling user to run in record mode.
All responses that must be structured are validated against JSON Schema (resources/schemas/*.json);
invalid → one repair attempt with the validation errors appended, then Validation failure.
Prompt templates in resources/prompts/<agent>.md; promptHash recorded in artifact lineage.
Never send secrets: a redactor scrubs env-like secrets from prompts.
```
Commit: `feat(agents): LLM gateway with record/replay and schema validation`

#### Step 5.2 — Sandboxed tools
**Prompt:**
```
Create tools: WorkspaceManager (JGit: create worktree at ORCH_WORKSPACE_ROOT/<runId> on new branch
agent/<runId> from main), FileTool (read/list/search; write only after PolicyEngine ALLOW or approved;
path canonicalization to block ../ traversal and symlink escape), DiffTool (unified diff vs base),
CommandTool (ProcessBuilder, exact-match allowlist from CMD-001, timeout 10 min, captures output,
kills process tree on timeout, no shell), GitCheckpointManager (implements core CheckpointManager),
CodeIndexTool (lists Java files, packages, public classes/methods, REST mappings via regex, Flyway
migrations — a lightweight repo map for brownfield reasoning).
Tests: traversal blocked, non-allowlisted command blocked, checkpoint/restore roundtrip on temp repo.
```
Commit: `feat(agents): sandboxed tools and git checkpoints`

#### Step 5.3 — Agents
**Prompt:**
```
Implement agents (each: prompt template + JSON schema + Java class implementing core Agent):
1. RequirementsAgent → requirements_spec {problemStatement, functional[], nonFunctional[],
   acceptanceCriteria[], assumptions[], ambiguities[{id, question, options[], severity BLOCKING|NONBLOCKING,
   proposedDefault}], outOfScope[], classification GREENFIELD|BROWNFIELD}.
   If BLOCKING ambiguities exist, gate spec.noBlockingQuestions fails → run AWAITING_HUMAN for answers
   (POST answers creates spec v2).
2. DecompositionAgent → task_graph {tasks[{id, title, type CODE|TEST|DOC|MIGRATION|CONFIG,
   requirementIds[], dependsOn[], estimatedFiles[], risk LOW|MED|HIGH}]}.
3. ImpactAnalysisAgent (uses CodeIndexTool) → impact_report {modules[], files[{path, changeType,
   reason}], apis[], dataFlows[], migrations[], riskNotes[], testsToUpdate[]}.
4. DesignAgent → design_doc {approach, alternativesConsidered[], apiChanges (OpenAPI fragment),
   schemaChanges (SQL), components[], risks[{risk, likelihood, impact, mitigation}], rolloutPlan}.
5. ImplementationAgent (per task) → patch {files[{path, newContent}]} — applied through FileTool
   (policy-checked), then checkpoint. Fallback strategy "minimal-patch": narrower prompt (single file,
   smallest change satisfying acceptance criteria).
6. TestAuthoringAgent → tests written from spec+design (not from implementation) to avoid
   confirmation bias.
7. DocsAgent → draft (README/API changes/ADR) and finalize (update with validation results).
8. ValidationAgent — DETERMINISTIC, no LLM: runs allowlisted mvn command, parses surefire + JaCoCo
   XML, runs PolicyEngine on full diff, computes diff stats → validation_report; feedback for loop-back.
9. ReleaseAgent → release_report {checklist[{item, status}], changelog, riskRegister, rollbackPlan,
   openIssues, finalEngineeringSummary (markdown)} [OR-16].
Unit tests per agent using ReplayLlmGateway fixtures (hand-written small fixtures are fine).
```
Commit: `feat(agents): SDLC agents` · 🏷️ `phase-5`

---

### PHASE 6 — Orchestrator App: Persistence, API, Metrics, Dashboard (Day 3, ~3 h) [OR-10, OR-11, D-1]

#### Step 6.1 — Persistence adapters
**Prompt:**
```
In orchestrator-app: Flyway migrations for orchestrator DB: run, stage_instance (projection table),
workflow_event (append-only: seq bigserial, run_id, type, stage_id, actor, payload jsonb, ts, prev_hash,
hash; REVOKE UPDATE/DELETE from app role via migration comment + trigger that raises on UPDATE/DELETE),
artifact (versioned, jsonb, lineage jsonb), approval. JDBC implementations of EventStore/ArtifactStore.
On startup: recover runs in RUNNING state via projector (interrupted stages → RETRYING).
```
Commit: `feat(orch-app): persistence with append-only audit`

#### Step 6.2 — REST API
**Prompt:**
```
Expose (X-API-Key; roles OPERATOR, APPROVER):
POST /api/runs {scenarioId? | requirementText, mode, autonomyLevel}
GET  /api/runs ; GET /api/runs/{id} (status + DAG nodes/edges with statuses)
GET  /api/runs/{id}/dag.mmd (Mermaid text with status colors)
GET  /api/runs/{id}/events ; GET /api/runs/{id}/audit/verify
GET  /api/runs/{id}/artifacts ; GET /api/artifacts/{aid} ; GET /api/artifacts/{aid}/lineage
POST /api/runs/{id}/answers {answers:[{ambiguityId, answer}]}
POST /api/runs/{id}/requirements {text}            (amend → re-plan)
GET  /api/approvals?status=PENDING ; POST /api/approvals/{id} {decision, comment}
POST /api/runs/{id}/halt ; POST /api/runs/{id}/resume ; POST /api/runs/{id}/rollback {toStage}
GET  /api/metrics/summary
OpenAPI via springdoc. MockMvc tests for authz (OPERATOR cannot approve).
```
Commit: `feat(orch-app): orchestration REST API`

#### Step 6.3 — Reliability metrics
**Prompt:**
```
MetricsRecorder (Micrometer): orchestrator.stage.executions{stage,outcome}, orchestrator.stage.retries,
orchestrator.fallbacks, orchestrator.rollbacks, orchestrator.replans, orchestrator.approvals.wait
(timer), orchestrator.run.duration (timer, e2e latency), orchestrator.llm.tokens, orchestrator.policy
.denials. /api/metrics/summary computes from events: run success rate, stage success rate,
retry rate, rollback frequency, MTTR (mean of [first failure → next success] per stage incident),
p50/p95 e2e latency, mean approval wait (reported separately so human time isn't hidden in agent
latency).
```
Commit: `feat(orch-app): reliability metrics incl. MTTR`

#### Step 6.4 — Dashboard
**Prompt:**
```
Single static page orchestrator-app/src/main/resources/static/index.html (vanilla JS + Mermaid from
CDN, no build step): runs list; selected run shows live DAG (poll 2s), stage table (status, attempts,
duration), pending approvals with diff/risk summary and Approve/Reject (comment) buttons, ambiguity
answer form, event timeline, artifact viewer, metrics summary, Halt/Resume/Rollback buttons, audit
verify badge. API key entered once and kept in memory only.
```
✅ You can run a whole replay scenario from the browser.
Commit: `feat(orch-app): operator dashboard` · 🏷️ `phase-6`

---

### PHASE 7 — The Three Scenarios (Day 3, ~2.5 h) [D-3, OR-1..16]

For each scenario: write `requirement.md`, run it in **record** mode once (needs an API key), review it, commit the `replay/` fixtures, and write `docs/scenarios/<name>.md` with the decomposition, a DAG screenshot or Mermaid, the approvals made, validation results and lessons learned. No API key? Ask Claude Code to hand-write the replay fixtures so they are consistent with the repo.

#### Step 7.1 — Greenfield: QR code for short links
`requirement.md`: *"Add an endpoint that returns a QR code PNG for a short link, with optional size (128–1024 px). It must respect link status."*
Shows: mode GREENFIELD → `impact_analysis` SKIPPED. Parallel implementation + tests + docs. A dependency add (ZXing in `pom.xml`) triggers **CHG-002 → approval**.
✅ The run completes. The new endpoint works on the merged main. Coverage gate is green.

#### Step 7.2 — Brownfield: link expiration + bug BF-1
`requirement.md`: *"Links should support an optional expiry (`expiresAt` or `ttlSeconds`). Expired links return 410 Gone. Also, users report deleted links still redirect for a while — fix it."*
Shows: ImpactAnalysis finds `LinkService`, `LinkCache`, the redirect controller, a new Flyway V3 migration (**CHG-001 → approval**) and cache TTL alignment (cache TTL ≤ time left before expiry). The decomposition links the bug fix to cache eviction plus the cross-instance invalidation note.
**Demonstrate on purpose:**
1. The first implementation attempt fails validation (fixture: a missing test update) → **loop-back with feedback** → passes. This shows the retry metric and MTTR.
2. 🧑‍⚖️ At design approval, **reject** with the comment "use 410 not 404 for expired" → design v2 → downstream invalidated → re-run.
3. A stage writes outside the path jail (fixture) → **DENY** → fallback minimal-patch.
✅ BF-1 regression test exists and passes. The audit verifies.

#### Step 7.3 — Ambiguous: "Protect the service from abuse"
`requirement.md`: *"Make the shortener safer — people are abusing it."*
Shows: RequirementsAgent returns BLOCKING ambiguities (malicious destination URLs vs request flooding vs spam account creation; block vs flag; which threat lists; do existing links get rescanned?). The run goes to AWAITING_HUMAN. You answer through `answers.json` (e.g., a domain blocklist checked at creation, admin can flag a link → 451, no external API). Spec v2 → plan. Then **mid-run amend**: *"also block URL shorteners chaining to other shorteners"* → **re-plan**. The design approval is voided, the downstream is invalidated and the run re-executes.
✅ The lineage view shows spec v1 → answers → spec v2 → amendment → spec v3, plus the decisions taken.

Commit after each scenario: `feat(scenarios): <name> with replay fixtures and write-up` · 🏷️ `phase-7`

---

### PHASE 8 — Test Hardening and Quality (Day 3, ~1.5 h) [NFR-6, OR-14]

#### Step 8.1 — Test pyramid completion
**Prompt:**
```
Add/verify:
- ArchUnit: orchestrator-core has no Spring/JGit/HTTP deps; agents depend on core ports only;
  shortener controllers don't access repositories directly.
- Orchestrator end-to-end tests (replay mode, Testcontainers Postgres, temp git repo copy) for all
  three scenarios asserting: final status, events sequence contains expected control events
  (APPROVAL_*, STAGE_RETRIED, REPLAN_TRIGGERED, ROLLED_BACK), audit verifies, metrics summary non-zero.
- Chaos tests: kill Redis during shortener integration test; coordinator crash/restart mid-run
  (recreate coordinator from events, finish run).
- Simple load test script (k6 or a JUnit-based benchmark) for redirect p99 on cache hit; record
  results in docs/testing.md.
- JaCoCo aggregate report; coverage ≥ 80% on shortener domain/service and orchestrator-core.
```
Commit: `test: architecture, e2e, chaos and performance tests`

#### Step 8.2 — Security pass
**Prompt:**
```
Review the codebase against: OWASP Top 10 for the shortener (SSRF, injection, auth, rate limit,
open-redirect considerations), and LLM-specific risks for the orchestrator (prompt injection via repo
content, excessive agency, insecure output handling, secrets in prompts). Write docs/security.md with
findings + mitigations in place + residual risks. Fix anything HIGH. Do not modify policies.yml
without asking me.
```
🧑‍⚖️ Read `docs/security.md` yourself.
Commit: `docs: security review`

---

### PHASE 9 — Documentation and Final Summary (Day 3, ~1.5 h) [D-2, D-4, D-5, OR-16]

#### Step 9.1 — Docs
**Prompt:**
```
Write:
- docs/architecture.md: components, orchestration model (DAG + coordinator + state machine), control
  flow sequence diagrams (Mermaid) for: normal run, approval reject → re-plan, validation failure
  loop-back, safe-stop/resume; data model; key decisions linking to ADRs.
- docs/setup.md: prerequisites, env, docker compose, build, run both apps, run each scenario in replay
  mode, run in live/record mode, troubleshooting.
- docs/testing.md: pyramid, what each layer proves, how to run, coverage numbers, perf results.
- docs/final-summary.md: plan & rationale, artifacts produced, risks/trade-offs, validation evidence,
  assumptions (A-1..A-5), limitations, future work. Pull real numbers from /api/metrics/summary.
- Root README.md: 1-minute pitch, quickstart (5 commands), links.
Additional ADRs: 0005-custom-orchestrator-vs-framework.md, 0006-single-writer-coordinator.md,
0007-event-sourced-audit.md, 0008-record-replay-llm.md, 0009-deterministic-validation.md.
```

#### Step 9.2 — Demo script
**Prompt:**
```
scripts/demo.sh: dev-up → build → start both apps → run greenfield (auto-approve via API as 'alice'
with printed approval summaries) → brownfield (scripted reject at design, then approve) → ambiguous
(post answers, then amend) → print metrics summary + audit verify for each run. Use set -euo pipefail,
jq, readable step banners. Also a 5-minute manual demo walkthrough in docs/demo.md.
```
✅ A fresh clone plus `./scripts/demo.sh` runs end-to-end in replay mode with no API key.
Commit: `docs: architecture, setup, testing, final summary, demo` · 🏷️ `v1.0.0`

---

## 8. Testing Strategy (summary)

| Layer | Tooling | Proves |
|---|---|---|
| Property-based | jqwik | Base62/Feistel bijection, DAG topological invariants |
| Unit | JUnit 5, AssertJ, Mockito, fakes | Services, validators, limiter, state machine, policies, gates, retry/backoff |
| Slice | MockMvc | API contracts, Problem Details, authz |
| Integration | Testcontainers (Postgres, Redis) | Migrations, cache fallback, analytics pipeline, event store immutability |
| Architecture | ArchUnit | Module boundaries (core is framework-free) |
| E2E orchestration | Replay LLM + temp git repo | Each scenario's control flow, audit integrity, metrics |
| Chaos | Container stop, coordinator restart | Graceful degradation, crash recovery |
| Performance | k6 / micro-benchmark | Redirect latency (NFR-1) |
| Static/Security | Spotless, SpotBugs (opt.), dependency-check (opt.), policy scan | Style, bug patterns, vulnerable deps, secrets |

---

## 9. Traceability Matrix

| Req | Implemented in | Verified by |
|---|---|---|
| FR-1..4 | Step 1.4 | LinkService unit, controller slice, IT |
| FR-5 | Step 2.3 | Analytics IT (100 clicks) |
| FR-6 | Step 1.4 | Idempotency tests |
| NFR-1 | 2.1, 2.4 | Perf test (8.1) |
| NFR-2 | 2.1, 2.2, 2.3 | Redis-down chaos test |
| NFR-3 | 1.3, 1.4, 2.2 | Validator tests, authz tests, security review |
| NFR-4 | 2.4 | Actuator/metrics IT |
| NFR-5 | 1.2 | jqwik properties |
| NFR-6 | 2.4, 8.1 | JaCoCo gate |
| OR-1 | 5.3 (Requirements), 7.3 | Ambiguous scenario E2E |
| OR-2 | 5.3 (Decomposition), 3.2 expand | Task graph gates, DAG tests |
| OR-3 | 5.2 CodeIndex, 5.3 Impact | Brownfield E2E |
| OR-4 | 3.2, 4.1, 6.3 yml | DAG + gate tests |
| OR-5 | 3.4 | Parallel overlap test |
| OR-6 | 3.3, 5.1 promptHash | Lineage API test |
| OR-7 | 4.3 | Approval tests, authz |
| OR-8 | 4.4 | Retry/fallback/rollback/halt tests |
| OR-9 | 4.2, 6.4 policies | Policy tests, brownfield DENY |
| OR-10 | 3.3, 6.1 | Audit tamper test, DB trigger test |
| OR-11 | 6.3 | Metrics summary test |
| OR-12 | 4.5 | Re-plan tests, ambiguous E2E |
| OR-13 | 5.3 | Scenario outputs merged and green |
| OR-14 | 5.3 Validation, 8.2 | Validation gates, security.md |
| OR-15 | 4.2 autonomy, 4.3 | Invariant tests |
| OR-16 | 5.3 Release, 9.1 | final-summary.md present per run |
| D-1..D-5 | Phases 6–9 | demo.sh on fresh clone |

---

## 10. Definition of Done (final checklist)

- [ ] `./mvnw verify` is green from a clean clone. The coverage gates pass.
- [ ] `docker compose up -d && ./scripts/demo.sh` completes all 3 scenarios in replay mode.
- [ ] Each run's `audit/verify` returns `valid: true`.
- [ ] Metrics summary shows success rate, retries, rollbacks, MTTR and e2e latency.
- [ ] The dashboard shows the DAG, approvals, the timeline and lineage.
- [ ] Docs: architecture, setup, testing, security, 3 scenario write-ups, final summary, ADRs 0001–0009.
- [ ] No secrets in the repo (`git log -p | grep -E "AKIA|PRIVATE KEY"` returns nothing).
- [ ] Known limitations are stated honestly in `final-summary.md`.

## 11. If You Fall Behind (cut order)

You can cut these first without breaking any core requirement: Grafana profile → k6 perf test (use the micro-benchmark) → dashboard polish (keep the API plus Mermaid) → dynamic task expansion (keep implementation as one stage, but keep the re-plan invalidation) → live-mode recording (hand-write the replay fixtures).
**Never cut:** approvals, the policy engine, the hash-chained audit, retries/rollback/safe-stop, re-plan, or the three scenarios.
