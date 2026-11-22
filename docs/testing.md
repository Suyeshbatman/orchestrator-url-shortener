# Testing

Run these in the repository root. Docker Desktop must be running.
```
.\mvnw.cmd -q verify                 # everything below except the scenario end-to-end runs (~4 min)
.\mvnw.cmd clean verify -Pe2e        # also the three scenarios end to end (~12 min)
```
`clean` matters for `-Pe2e` after a failed run: failsafe merges each run into its summary file, so
stale failures from the earlier run would fail the new one.

## The pyramid
| Layer | What it proves | Where |
|---|---|---|
| Unit | services, engine, agents, gates, policies, parsers (JUnit 5, AssertJ, Mockito where a port is faked) | every module, `*Test` |
| Property | Base62/Feistel bijection, DAG ordering (jqwik) | `Base62Test`, `FeistelPermutationTest`, `orchestrator-core` DAG tests |
| Architecture | ArchUnit rules, listed below | `ArchitectureTest` in core, agents and the shortener |
| Integration | real Postgres and Redis through Testcontainers (`postgres:16-alpine`, `redis:7-alpine`); nothing mocks the database | `*IT` |
| End to end | the three scenarios through the orchestrator's HTTP API on Postgres, real agents replaying fixtures, real Git, a real Maven build of the shortener inside each run | `ScenariosE2E` (`-Pe2e`) |
| Chaos | the failure modes listed below | |
| Load | redirect latency of cached links over HTTP | `RedirectLatencyIT` |

**Architecture rules**
- `orchestrator-core` depends on no Spring, Jakarta, JGit, HTTP client or LLM SDK, and on no other
  module.
- `orchestrator-agents` reaches a run only through the core's ports (`StageContext`, the policy
  engine, the checkpoint and schema interfaces). It never uses the coordinator, its command bus, the
  event or artifact stores, or an in-memory adapter, and it never uses Spring.
- Shortener controllers (`@RestController`) never touch a repository, JDBC or Redis directly.

**Fixtures are pinned.** A fixture matches only the exact prompt it was recorded with, and the
prompts include a map of `shortener-service` and its `pom.xml`. So each `scenario.yml` names its
base as `base: phase-7`, the tag whose `shortener-service` code the fixtures were recorded against,
and not `main`, which moves. A deliberate change to the shortener that should reach the scenarios
means re-recording them (`record-fixtures <id>` or `run-scenario <id> --llm record`) and moving the
pin. To run a scenario from the dashboard, the source repository's `main` must be at that commit
too.

**End to end (`ScenariosE2E`).** Each scenario starts from a bare copy of this repository, reset to
the scenario's base commit, and the test plays its humans over the API. That means answers,
approvals, the design rejection in 7.2 and the amendment in 7.3. Each test checks that:
- the run ends as `scenario.yml` expects;
- the scenario's control events appear: `APPROVAL_*`, `STAGE_SKIPPED`, `APPROVAL_REJECTED`,
  `STAGE_INVALIDATED`, `FALLBACK_USED` with the `DENY` behind it, and `REPLAN_TRIGGERED` twice;
- the hash chain verifies;
- the metrics summary is non-zero.

Run time on the reference machine: 429 s for all three. Rollbacks (`ROLLED_BACK`) occur in none of
the scenarios. The core's `RetryAndLoopTest` and `SafeStopAndRecoveryTest` cover them.

**Chaos**
| Failure | Test | Expected |
|---|---|---|
| Redis killed mid-test | `RedisCacheIT.redisDownFallsBackToTheDatabase`, `redisDownIsDegradedNotDown` | redirects keep working from Postgres; health is DEGRADED (200), still ready |
| Orchestrator crash mid-run | `RunRecoveryIT.afterARestartAnInterruptedRunIsResumedAndTheLostAttemptRetried` | a new coordinator rebuilds the run from the event log on Postgres, retries the lost attempt and finishes |
| Safe stop and kill | `SafeStopAndRecoveryTest` (core) | grace period, checkpoint, `RUN_HALTED`; resume continues |
| LLM down | `AnthropicLlmGatewayTest` | retries, then the fallback model; the circuit breaker opens |

## Redirect latency (cache hit)
`RedirectLatencyIT` runs the shortener on a random port with real Postgres and Redis. It creates 100
links and warms the caches with 2,000 requests. Then it measures one client (the latency of a single
redirect) and 32 concurrent clients (throughput). Each request goes over HTTP through the whole hot
path: the filters, the Redis-backed rate limiter, the L1 cache hit, the click publish, and the 302.

Measured on the development machine (Windows 11, Docker Desktop, JDK 21), without the coverage agent:
```
.\mvnw.cmd -q -pl shortener-service verify "-Dit.test=RedirectLatencyIT" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```
| clients | requests | p50 ms | p95 ms | p99 ms | max ms | req/s |
|---|---|---|---|---|---|---|
| 1 | 5000 | 1.69 | 3.87 | 4.79 | 8.66 | 488 |
| 32 | 20000 | 16.51 | 24.95 | 28.46 | 32.79 | 1842 |

- **One client.** A cached redirect takes p99 under 5 ms, and most of that is the rate limiter's
  round trip to Redis in Docker.
- **32 clients.** The latency is mostly waiting in line; the service handles about 1,800
  redirects/s on one laptop.
- **During `verify`.** The coverage agent roughly halves throughput. The test then asserts only a
  loose ceiling (single-client p99 < 100 ms), so slow machines don't fail the build. The table is
  written to `shortener-service/target/benchmark/redirect-latency.md` on every run.

## Coverage
Gates (line coverage per package, unit and integration tests combined; `verify` fails below them):
- `shortener-service`: every feature package at least 80 % (`config` wiring excluded);
- `orchestrator-core`: every package at least 80 %.

The aggregate report covering every module is at
`coverage-report/target/site/jacoco-aggregate/index.html` after `verify`. Latest:

| Module | Lines | Covered |
|---|---|---|
| shortener-service | 1217 | 95.2 % |
| orchestrator-core | 3318 | 95.9 % |
| orchestrator-agents | 1332 | 92.9 % |
| orchestrator-app | 1006 | 97.7 % |
| orchestrator-cli | 417 | 40.8 % (the scenario runner; exercised by the scenarios, not by `verify`) |
| **all** | 7290 | **92.3 %** |
