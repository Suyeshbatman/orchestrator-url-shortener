# ADR-0009: Every pass/fail decision comes from deterministic code, not from a model

- **Status:** Accepted
- **Date:** 2026-11-21
- **Scope:** `orchestrator-core` (`gate`, `policy`), `orchestrator-agents` (`ValidationAgent`)

## Context
Models are poor judges of their own output. "The tests pass" from a model counts for nothing. The
live recordings showed code that called methods which don't exist, and tests that disagreed with
the code on configuration. Both would have looked plausible in prose.

## Decision
The LLM proposes, deterministic code disposes, humans approve.
- **Gates are code.** Examples: schema validity, "every requirement is covered by a task", "no
  blocking questions", build green, tests green, coverage at least 80 %, no policy DENY, release
  checklist complete. Entry gates stop a stage from running on bad input; exit gates stop bad
  output from spreading.
- **Validation is a tool, not an agent opinion.** `ValidationAgent` makes no LLM call. It:
  - runs the allowlisted Maven build in the run's workspace;
  - parses the Surefire/Failsafe XML and the JaCoCo CSV;
  - diffs the workspace against the base;
  - has the policy engine judge the diff.
- **The policy engine is code over YAML.** It handles the path jail, forbidden paths, the command
  allowlist, secret and PII patterns, blast radius, and approvals for migrations, dependencies,
  security config and unreviewed model code. Its invariants live in code, and the policy hashes are
  recorded in every run.
- **Independent tests.** Tests come from a separate agent that sees the spec and the design, never
  the implementation.

## Consequences
- A failing build is a fact with a report. It loops back to implementation with the `[ERROR]` lines
  as feedback, instead of being argued with. Scenario 7.2 shows it: the loop-back's attempt to skip
  the failing test was denied, and the minimal fix passed.
- Validation costs a real build per attempt (1–3 minutes) and needs Docker for the shortener's
  Testcontainers tests.
- Model-written code runs during that build. CHG-005 has a human review it first in live mode, and
  `docs/security.md` covers the remaining risk.
