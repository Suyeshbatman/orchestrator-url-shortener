# ADR-0008: LLM calls are recorded and replayed by the hash of their exact request

- **Status:** Accepted
- **Date:** 2026-11-21
- **Scope:** `orchestrator-agents` (`llm`), `orchestrator-cli` (scenario runner)

## Context
Demos and tests must give the same result every time, without an API key or API cost, and a change
to a prompt must not drift silently. Calls to a live model are slow, cost money and give different
replies on different days.

## Decision
- **One gateway, several modes.** `LlmGateway` has `live`, `record` and `replay` implementations,
  each wrapped by `RedactingLlmGateway`, which removes secrets before anything leaves the process.
  `replay` is the default (`ORCH_LLM_MODE`).
- **Fixtures are keyed by the request.** A fixture is `replay/<sha256>.json`, keyed by the hash of
  the agent, system prompt, user prompt and JSON schema. A missing fixture is a hard
  `ReplayMissingException` naming the request hash. In the CLI, the request is also saved under
  `replay-missing/` for comparison.
- **Fixtures can be hand-written.** `record-fixtures <id>` writes each agent's exact prompt to
  `replies/pending/` and records the reply written for it. That is how the scenarios were finished
  when the API credit ran out, and how deliberate failures are forced.
- **Prompt inputs must not change between runs.** That rules out run ids, timestamps, timings,
  workspace paths, map ordering and noisy coverage digits. The rule was enforced by fixing every
  case found:
  - the loop-back feedback no longer carries the run id;
  - the build summary keeps only `[ERROR]` lines, without timings or workspace paths;
  - coverage is reported to the percent;
  - answers are stored as canonical JSON;
  - the validation report is written as canonical JSON.
- **Scenarios are pinned.** Each scenario names the commit whose `shortener-service` code its
  fixtures were recorded against (`base: phase-7`).

## Consequences
- The three scenarios replay event for event from the CLI, the dashboard and the end-to-end tests.
- A prompt change shows up as a missing fixture, so it can never pass unnoticed.
- **Replay proves the orchestration, not the model.** It doesn't show that a live model would
  produce the same code tomorrow. The live recordings for scenario 7.1 are what found the real
  problems: timeouts, missing code context and a code/test contract gap (see
  `docs/scenarios/greenfield-qr-code.md`).
- Changing the shortener, a prompt or a schema on purpose means re-recording the affected scenarios.
