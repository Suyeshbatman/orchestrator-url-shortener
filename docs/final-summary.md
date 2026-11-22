# Final summary

## What was asked, and the plan
**What was asked:** build an *agentic SDLC orchestrator*. It must take a requirement through every
lifecycle stage using LLM agents, governed by gates, policies, human approvals, failure handling
and an audit trail. And it must prove itself on a real target system under three kinds of change:
new functionality, a change to existing behaviour with a bug fix, and an ambiguous request.

**The plan** (`docs/plan/BUILD_PLAN.md`; the reasons are in `DESIGN_RATIONALE.md`):
- **A target worth changing.** A production-quality URL shortener, so brownfield changes meet
  real caches, migrations, contracts and tests.
- **Our own engine, not a framework** (ADR-0005). The orchestration is the deliverable, so every
  control path is code: a YAML DAG, a single-writer coordinator over an explicit state machine
  (ADR-0006), and an event-sourced, hash-chained log (ADR-0007).
- **The LLM proposes, deterministic code disposes, humans approve** (ADR-0009). Gates, the policy
  engine and a real Maven build decide; approvers decide at the points that matter.
- **Record and replay** (ADR-0008), so every demo and test is reproducible without an API key.
- **One approved step at a time**, each verified with `mvnw verify` before the next.

## What was produced
| Artifact | Where |
|---|---|
| URL shortener: Feistel/Base62 codes, aliases, 302 redirects, L1+L2 cache with negative caching and a Redis breaker, rate limiting, async analytics, idempotency, RFC 9457 errors, metrics, health | `shortener-service/`, ADR-0001–0004 |
| Engine: workflow loader, DAG with parallel branches and joins, coordinator, 11-state stage machine, entry and exit gates, policy engine with autonomy levels, budgets, retries with jitter, fallbacks, loop-backs, Git checkpoints and rollback, safe stop and kill, recovery, re-planning | `orchestrator-core/` |
| 9 SDLC agents with prompts and JSON Schemas; LLM gateway (live / record / replay, redaction, schema repair, circuit breaker, fallback model); sandboxed tools | `orchestrator-agents/` |
| REST API with operator and approver roles, Postgres event and artifact stores (append-only triggers), projections, lineage, metrics, dashboard (DAG, approvals, answers, amendments, lineage, audit) | `orchestrator-app/` |
| Scenario runner and fixture recorder (`run-scenario`, `record-fixtures`) | `orchestrator-cli/` |
| Three scenarios with fixtures, scripted humans and write-ups | `scenarios/`, `docs/scenarios/` |
| Docs: architecture, setup, testing, security, 9 ADRs, known issues | `docs/` |

## Validation evidence
**Build.** `.\mvnw.cmd -q verify` passes:
- **833 tests**: unit, property, architecture and integration, with real Postgres and Redis;
- formatting checks and coverage gates;
- about 4 minutes.

**Scenarios end to end.** `.\mvnw.cmd clean verify -Pe2e` runs all three through the HTTP API on
Postgres in about 420 s, and
`SandboxedScenarioE2E` runs 7.1 again with every build in the Docker sandbox (197 s). Each one:
- uses the real agents replaying fixtures, real Git workspaces, and a real build of the shortener;
- asserts the final status, its control events, a valid hash chain and non-zero metrics.

The dashboard run of 7.3, with a person as approver, matched the CLI run event for event (205
events, valid audit). The demo (`orchestrator-cli demo`, `docs/demo.md`) runs all three through
the API on a fresh database in 440 s and ends with every audit chain valid.

| Scenario | Events | Shows |
|---|---|---|
| 7.1 greenfield QR code | 100 | impact analysis skipped; parallel implementation, tests and docs; dependency approval (CHG-002); 278 tests green at validation |
| 7.2 brownfield expiry + BF-1 | 144 | design rejected ("use 410 not 404") and redone; migration approval (CHG-001); validation failure and loop-back; a denied attempt to skip a test (SEC-002) and the minimal-patch fallback; BF-1 regression tests |
| 7.3 ambiguous abuse | 205 | 4 blocking questions, answers, spec v2; a mid-run amendment that invalidates 7 stages and voids approvals; spec v3; 277 tests green |

**Reliability metrics.** From `GET /api/metrics/summary`, after the three end-to-end runs (raw JSON
in `orchestrator-app/target/e2e/metrics-summary.json`):

| Metric | Value |
|---|---|
| Runs | 3 total, 3 completed, 0 failed, 0 halted. Run success rate **1.0** |
| Stages | **39** succeeded, 0 failed: stage success rate **1.0**. A stage completed by an approval counts |
| Stage attempts | 44 (39 successes, plus the attempts ended by the rejection, the loop-back, the denial and the two re-plans) |
| Retries / fallbacks / rollbacks | 0 / **1** (the DENY → minimal patch in 7.2) / 0 |
| MTTR | **44.9 s** over 2 resolved incidents (the 7.2 validation loop-back, and the denied write and its fallback), 0 open |
| End-to-end latency, p50 / p95 | 111 s / 189 s (excluding approval wait: 106 s / 183 s) |
| Approval wait | 21 decisions, mean 0.73 s, max 1.03 s (scripted approver) |

These are replay runs. The latency is the orchestration plus the real Maven builds; there is no
model time. Live calls took 1–15 minutes each for large replies.

**Quality.**
- **Coverage:** 92.3 % of lines overall, core 95.9 %, shortener 95.2 %. Gates: at least 80 % per
  package in core and the shortener.
- **Redirect latency on a cache hit:** p99 4.8 ms with one client, about 1,840 req/s with 32 clients.
- **Security review:** one HIGH finding, fixed (CHG-005). See `docs/security.md`.

## Assumptions
| | Assumption | How it held |
|---|---|---|
| A-1 | Single region, single node; the design must allow horizontal scaling | Held. The shortener is stateless apart from Postgres and Redis. The orchestrator recovers runs from the log, but one instance owns them (no distributed lease). |
| A-2 | The LLM is optional at runtime; replay gives deterministic demos and tests | Held, and it was needed. Replay is the default everywhere; no API key is needed to build, test or demo. |
| A-3 | Approvers are API keys with role APPROVER; no SSO | Held. Operator and approver keys are separate; only a human approver principal counts. |
| A-4 | Analytics are at-most-once; losing clicks under overload is acceptable, slowing redirects is not | Held. A bounded queue drops on overflow (ADR-0004); redirects never wait for analytics. |
| A-5 | "Publish" is a human-approved local merge into `main`; no CI/CD deploy | Held. `publish` is approved before it runs and merges into the run's (bare) source repository. |

## Trade-offs and risks
- **Replay versus live.** Fixtures make everything reproducible and free. But they prove the
  orchestration, not the model. The live recordings for 7.1 found real orchestrator problems that
  replay never could:
  - fixed timeouts too short for large replies;
  - greenfield agents blind to the existing code;
  - code and tests disagreeing on configuration;
  - non-deterministic prompt inputs.

  All are fixed.
- **Hand-written fixtures.** The API credit ran out during the 7.1 recordings, so the fixtures for
  all three scenarios (and the fan-out scenario) were written by hand against each agent's exact prompt. The code was built and
  tested before use. The write-ups say so. Two replies in 7.2 are deliberately bad.
- **Fixtures are pinned to a commit** (`base: phase-7`). Changing the shortener, a prompt or a schema
  on purpose means re-recording.
- **Model-written code runs during validation.** In live mode a human reviews it first (CHG-005),
  and it is built in a Docker sandbox with a private Docker daemon and no host credentials. The cost
  is about 1.5 minutes more per build. The sandbox still has network access.
- **One coordinator thread per run.** It's simple and deterministic, but not distributed.

## Limitations
- **L-1** Replay shows the orchestration, not that a live model would produce the same code again.
- **L-2** Sandboxed validation builds still have network access: Maven and the test images are
  downloaded. There is no egress allowlist yet.
- **L-3** The scenario changes stay in each run's own repository and are not merged into this
  repository's `main`. `main` still has BF-1 on purpose.
- **L-4** One orchestrator instance owns the runs: no leader election, no work distribution.
- **L-5** In-process L1 cache entries on other instances can serve a deleted or flagged link for up
  to 5 minutes.
- **L-6** The hash chain is tamper-evident, not tamper-proof. A database superuser could rewrite it.
  The head hash is not anchored externally.
- **L-7** Latency figures come from replay runs, without model time. Live calls take minutes each.
- **L-8** Live calls are non-streaming, so very large replies take many minutes.
- **L-9** The per-task fan-out is opt-in (`sdlc-fanout.yml`). Only one scenario uses it
  ([greenfield-fanout-notes](scenarios/greenfield-fanout-notes.md), hand-written, in `-Pe2e` only).
  The three Phase 7 scenarios use `sdlc-standard.yml`, where implementation is one stage over all
  tasks.
  - Tasks that touch the same files are serialised only on their *estimated* files.
  - An unannounced overlap is caught when the file is written, and that task is retried.

## Future work
- **An egress allowlist for the sandbox** (Maven Central and the container registry only), closing
  L-2.
- **Stream live calls**, and record a fully live run of each scenario when credit allows. Compare
  live and hand-written fixtures.
- **Cross-instance cache invalidation** over Redis pub/sub, closing L-5.
- **Anchor the audit head hash** in write-once storage, and sign events.
- **Run ownership** across orchestrator instances (a lease per run), or move the engine onto
  Temporal (ADR-0005).
