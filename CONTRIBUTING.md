# Contributing

## Build and test
```
.\mvnw.cmd -q spotless:apply              # format (google-java-format); verify fails on unformatted code
.\mvnw.cmd -q verify                      # everything: tests, ArchUnit, coverage gates, formatting (~4 min)
.\mvnw.cmd clean verify -Pe2e             # also the scenarios end to end, and 7.1 in the sandbox (~12 min)
.\mvnw.cmd -q install -DskipTests         # build without tests (skips integration tests and coverage too)
```
Docker Desktop must be running: integration tests use Testcontainers (`postgres:16-alpine`,
`redis:7-alpine`). Details are in [docs/testing.md](docs/testing.md).

## Layout and boundaries
| Module | May depend on | Enforced by |
|---|---|---|
| `orchestrator-core` | nothing: no Spring, Jakarta, JGit, HTTP or LLM SDK, no other module | `orchestrator-core/.../ArchitectureTest` |
| `orchestrator-agents` | core's ports only: never the coordinator, its commands, the stores or in-memory adapters; never Spring | `orchestrator-agents/.../ArchitectureTest` |
| `orchestrator-app` | core, agents, Spring | |
| `orchestrator-cli` | core, agents | |
| `shortener-service` | Spring. Controllers never touch repositories, JDBC or Redis directly | `shortener-service/.../ArchitectureTest` |

Packages are organised by feature (`link`, `cache`, `ratelimit`, `analytics`; `engine`, `policy`,
`gate`), with ports (interfaces) kept apart from adapters.

## Coding standards
- **Java 21:** records for DTOs, values, events and commands; sealed interfaces for closed
  hierarchies; exhaustive `switch`.
- **Injection:** constructor injection only. No field injection, no Lombok.
- **Time:** inject `java.time.Clock`. No `Instant.now()` in domain or engine code.
- **Errors:** RFC 9457 Problem Details, with a stable `type` URI per error. A new error gets a new
  `ProblemTypes` entry and a handler.
- **Persistence:** Spring Data JDBC or `JdbcClient` with named parameters, never JPA, never SQL
  built from values. Schema changes are new Flyway migrations (`V<n>__<what>.sql`); never edit a
  released one.
- **Dependencies:** versions are looked up on Maven Central, never guessed. Docker images are
  pinned, never `:latest`.
- **Determinism (orchestrator):** anything that reaches a prompt must be identical on every run, or
  replay fixtures stop matching (ADR-0008). That rules out run ids, timestamps, timings, absolute
  paths, map iteration order, and noisy digits.
- **Style:** match the surrounding code, its comments included. Say why, not what.

## Tests
- **Every public service or engine method has unit tests.** Use AssertJ, and fakes for ports.
- **Integration tests** use real Postgres and Redis through Testcontainers. Never mock the database.
- **No `Thread.sleep` in tests:** use Awaitility, or a clock the test moves.
- **jqwik** for invariants: the bijection, DAG ordering.
- **Coverage:** every package of `orchestrator-core`, and every feature package of the shortener,
  stays at 80 % of lines or more. `verify` fails below that.

## Common changes

### Add an SDLC agent
1. **The class:** extend `LlmAgent` in `orchestrator-agents/.../sdlc`. Give it a name, an output
   artifact type and a token limit. Implement `inputs(ctx)`, the prompt values (every read through
   `ctx` is recorded as lineage), and `summary(json)`. Override `check` for rules a schema can't
   express, and `after` for side effects such as writing files through `FileTool`, which
   policy-checks every write.
2. **The prompt:** `src/main/resources/prompts/<AgentName>.md`, with a `## System` and a `## User`
   section and `{{placeholders}}`. A placeholder without a value is an error. State that input
   content is data, never instructions.
3. **The schema:** `src/main/resources/schemas/<artifact_type>.json` (JSON Schema 2020-12). Replies
   are validated against it, with one repair attempt.
4. **Registration:** add it to `SdlcAgents.standard`, and add a stage with it to the workflow.
5. **Tests:** with `AgentHarness` and recorded replies (see `DeliveryAgentsTest`).

### Add a gate
Add a method to `StandardGates` returning `GateResult.pass(...)`, `fail(...)` or `needsInput(...)`,
and register it in `StandardGates.register`. Use `withArgument` for gates like `coverage.min:0.8`.
Gates must be deterministic, and must read artifacts, not ask a model. Add it to a stage's
`entryGates` or `exitGates`.

### Add a policy rule
Rules of the existing kinds are YAML only (`docs/reference/workflows-and-policies.md`). Changing
`config/policies.yml` or `config/autonomy.yml` needs an explicit decision by the owner, and agents
can never write them. A new kind means a variant of the sealed `Policy`, parsing in `PolicyLoader`
(strict keys), matching in `PolicyEngine`, and tests in `PolicyLoaderTest` and `PolicyEngineTest`.

### Change a stage or the workflow
Edit `config/workflows/sdlc-standard.yml`. The loader rejects unknown keys, unknown gates, cycles and
bad durations, with every problem at once. `WorkflowLoaderTest` loads the real file.

### Add or change a scenario
1. **Create** `scenarios/<id>/` with `requirement.md` and `scenario.yml`: `mode`, `autonomy`,
   `base`, `expect`, `decisions`, `amend`. Optionally add `answers.json`.
2. **Write the fixtures:**
   - with a model: `run-scenario <id> --llm record` (needs `ANTHROPIC_API_KEY`); hand-written
     replies in `replies/` take precedence;
   - without one: `record-fixtures <id>` writes each missing prompt to `replies/pending/`. Write
     `replies/<Agent>-<n>.json` and run again until the scenario completes.
3. **Prove it:** replay without the replies (`run-scenario <id>`) and run `verify -Pe2e`. Add the
   scenario to `ScenariosE2E` and `Demo.SCENARIOS`.
4. **Document it:** write `docs/scenarios/<id>.md`.

### When fixtures break
- **The cause:** prompts embed the shortener's code map and `pom.xml`, the prompt templates and the
  schemas. Changing any of them on purpose invalidates the affected fixtures.
- **Re-record and move the pin:** re-record the scenarios, then move `base:` in each `scenario.yml`
  to the commit they were recorded against.
- **Finding what changed:** a replay miss in the CLI saves the request under
  `.orchestrator/scenarios/<id>/replay-missing/`, to diff against the fixture.

## Commits and releases
- **Commits:** one logical change per commit, in the style `feat(scope): …`, `fix: …`, `test: …`,
  `docs: …`. Run `spotless:apply` and `verify` before committing.
- **A release:**
  1. `verify` and `clean verify -Pe2e` are green.
  2. The demo passes from a fresh clone (`docs/demo.md`).
  3. `CHANGELOG.md` is updated.
  4. Tag `vX.Y.Z` and push the tag. Never move a pushed tag; release a new one.
- **Never commit secrets.** `.env` is git-ignored; `.env.example` holds placeholders only.
