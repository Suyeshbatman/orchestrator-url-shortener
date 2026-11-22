# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-11-22
### Added
- **Sandboxed validation builds** (`DockerSandbox`). Builds of unreviewed model output (live and
  record modes) run in throw-away containers:
  - a private Docker daemon for the shortener's Testcontainers tests;
  - a build container that sees only the workspace and a Maven cache;
  - no credentials, all capabilities dropped, resource limits.

  Configured with `ORCH_VALIDATION_SANDBOX=auto|docker|none` (default `auto`), and `--sandbox` in
  the CLI.
- `SandboxedScenarioE2E`: scenario 7.1 end to end with every build in the sandbox.

### Fixed
- **Reliability metrics:**
  - stages completed by an approval now count as successes (stage success rate: 22 → 39 of 44
    attempts in the scenario runs);
  - validation loop-backs count as incidents in MTTR.

## [1.0.1] — 2026-11-21
### Fixed
- `install -DskipTests` also skips the integration tests and the coverage gate. A fresh clone could
  not build without running them.
- The demo runs its own Postgres (compose project `orchestrator-demo`, port 5435), so it needs no
  `.env` and doesn't touch the developer's containers.

## [1.0.0] — 2026-11-21
### Added
- **URL shortener:**
  - Feistel/Base62 codes and custom aliases;
  - 302 redirects;
  - a two-level cache (Caffeine and Redis) with negative caching and a Redis circuit breaker;
  - token-bucket rate limiting (a Redis Lua script with a local fallback);
  - asynchronous at-most-once click analytics with HyperLogLog unique visitors;
  - idempotent creation, RFC 9457 errors, metrics and health.
- **Orchestrator engine:**
  - a YAML DAG of SDLC stages with gates, parallel branches and joins;
  - a single-writer coordinator over an explicit 38-transition state machine;
  - a policy engine with autonomy levels and invariants;
  - human approvals with separation of duties;
  - retries with full jitter, fallbacks, loop-backs, Git checkpoints and rollback;
  - safe stop, kill, budgets, crash recovery and re-planning;
  - a hash-chained, append-only audit log with lineage.
- **Agents:** nine SDLC agents with prompts and JSON Schemas; an LLM gateway (live, record and
  replay; redaction; schema repair; circuit breaker; fallback model); tools (file, diff, command,
  code index, Git).
- **App and CLI:**
  - a REST API with operator and approver roles, Postgres stores, metrics and a dashboard;
  - a CLI (`run-scenario`, `record-fixtures`, `demo`, `verify-audit`, `metrics`).
- **Scenarios and tests:**
  - three replayable scenarios: greenfield QR codes; brownfield link expiry plus the BF-1 fix;
    an ambiguous request with answers and an amendment;
  - tests: unit, property, ArchUnit, Testcontainers integration, end to end, chaos, and redirect
    latency;
  - coverage gates.
- **Docs:** architecture, setup, testing, security review, demo, final summary, ADRs 0001–0009.

### Security
- CHG-005: unreviewed model-written code needs human review before validation builds it.
