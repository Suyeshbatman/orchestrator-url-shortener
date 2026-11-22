# ADR-0005: A custom orchestrator in plain Java, not an agent framework or workflow engine

- **Status:** Accepted
- **Date:** 2026-11-21
- **Scope:** `orchestrator-core`, `orchestrator-agents`, `orchestrator-app`

## Context
The orchestration layer is the thing being built: a DAG of SDLC stages with entry and exit gates,
parallel branches and joins, a policy engine, human approvals, retries, rollback, safe stop,
re-planning and an audit log. The options were an agent framework (LangGraph, CrewAI), a durable
workflow engine (Temporal, Camunda), or our own engine.

## Decision
Build the engine in plain Java 21 (`orchestrator-core`, no Spring, no Git, no HTTP; ArchUnit enforces
this). Keep the concepts of a durable workflow engine:
- an event-sourced history (ADR-0007);
- a deterministic single-writer coordinator (ADR-0006);
- agents as activities behind a port (`Agent`, `StageContext`).

Spring Boot is used only at the edges: the REST API, persistence and the dashboard.

| Option | Why not |
|---|---|
| LangGraph / CrewAI | Graph-shaped prompting, not governance: no policy engine, no tamper-evident audit, no separation of duties for approvals. It would also mean a second language next to the Java target system. |
| Temporal / Camunda | Excellent, but heavy infrastructure for a prototype, and they would turn "design an orchestration layer" into configuration. |

## Consequences
- Every control path (gates, retries, fallbacks, loop-backs, re-plans, approvals, safe stop,
  recovery) is code we can read, test and explain. The core has 95.9 % line coverage.
- The validation stage runs the same build tool the humans use.
- We own the engine's bugs. The explicit state machine (`StageStatus`, 38 legal transitions) and
  the event log keep that tractable.
- Migrating to Temporal later is mostly mechanical: events become workflow history, the
  coordinator becomes workflow code, agents become activities.
