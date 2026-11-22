# Agentic SDLC Orchestrator + URL Shortener

An orchestrator that takes a requirement through the software lifecycle. It writes the
specification, plans, analyses the impact, designs, implements, writes tests and docs, validates,
prepares the release and publishes, using LLM agents on a real codebase. It is **governed** at
every step:
- a YAML DAG of stages with entry and exit gates, parallel branches and joins;
- a policy engine (path jail, command allowlist, secret and PII scans, change approvals);
- human approvals with separation of duties;
- retries, fallbacks, loop-backs, rollback, safe stop and re-planning;
- a hash-chained, append-only audit log.

The codebase it changes is a production-quality **URL shortener**: Feistel-coded short links, a
two-level cache, rate limiting, analytics and RFC 9457 errors.

The point is the control, not the model: **the LLM proposes, deterministic code disposes, humans
approve.** Three recorded scenarios show it end to end:
- **greenfield:** a QR code endpoint;
- **brownfield:** link expiry plus fixing a seeded cache bug, with a rejected design, a failed
  build, a denied shortcut and a fallback;
- **ambiguous:** blocking questions, answers and a mid-run amendment that re-plans the run.

They replay deterministically, with no API key.

## Quickstart
Needs JDK 21 and Docker Desktop running. No API key and no configuration.
```
git clone <this repository> orchestrator-url-shortener; cd orchestrator-url-shortener
.\mvnw.cmd -q install -DskipTests
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario greenfield-qr-code"
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario brownfield-expiry-bf1"
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario ambiguous-abuse"
```
Each run prints its events live: approvals, rejections, loop-backs, policy decisions, re-plans. It
ends with `COMPLETED` and `audit valid`. Each run makes real Git commits in a sandboxed copy of the
repository and runs the shortener's full Maven build with Testcontainers. Use `./mvnw` on macOS and
Linux.

The full demo runs the same three scenarios through the orchestrator's HTTP API on Postgres, with
printed approval summaries, audit checks and the metrics summary. It takes about 8 minutes and
starts its own Postgres (Docker, port 5435), so it needs no `.env` either:
```
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=demo"
```
For the 5-minute manual walkthrough of the dashboard, see [docs/demo.md](docs/demo.md).

To run both services, the dashboard and live mode, see **[docs/setup.md](docs/setup.md)**. The
shortener runs on port **8085** and the orchestrator on port **8086**.

## Documentation
| | |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Components, the orchestration model, control-flow diagrams, data model, decisions |
| [docs/setup.md](docs/setup.md) | Prerequisites, environment, running everything, live and record mode, troubleshooting |
| [docs/demo.md](docs/demo.md) | The automatic demo and a 5-minute manual walkthrough of the dashboard |
| [docs/testing.md](docs/testing.md) | The test pyramid, end-to-end and chaos tests, latency results, coverage |
| [docs/security.md](docs/security.md) | OWASP and LLM-specific review, mitigations, residual risks |
| [docs/final-summary.md](docs/final-summary.md) | What was built, the evidence, trade-offs, limitations, future work |
| [docs/scenarios/](docs/scenarios/) | The three scenario write-ups |
| [docs/adr/](docs/adr/) | Architecture decision records 0001–0009 |
| [docs/known-issues.md](docs/known-issues.md) | BF-1, the seeded defect the brownfield scenario fixes |
| [docs/plan/](docs/plan/) | The build plan and the design rationale |

## Repository layout
```
shortener-service/     the target system (Spring Boot, :8085)
orchestrator-core/     the engine: DAG, coordinator, state machine, gates, policies (plain Java)
orchestrator-agents/   the SDLC agents, the LLM gateway (live / record / replay), tools
orchestrator-app/      REST API, Postgres event store, dashboard, metrics (Spring Boot, :8086)
orchestrator-cli/      run-scenario, record-fixtures
coverage-report/       aggregate JaCoCo report
config/                workflows/sdlc-standard.yml, policies.yml, autonomy.yml
scenarios/             requirement, scenario.yml, fixtures and hand-written replies per scenario
```
