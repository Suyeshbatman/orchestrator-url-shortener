# ADR-0006: One coordinator thread owns all run state; agents run in parallel workers

- **Status:** Accepted
- **Date:** 2026-11-21
- **Scope:** `orchestrator-core` (`engine.Coordinator`)

## Context
Parallel stages (implementation, test authoring and docs) finish at unpredictable times. If every
worker updates the run's state, you get double-dispatched joins, lost updates and an audit order
that depends on thread timing. That would make the hash chain and replay meaningless.

## Decision
Use the actor / single-writer pattern:
- **One coordinator thread** per run consumes `Command`s from a queue. Commands are human
  decisions, `StageCompleted` and `StageFailed` from workers, timers, amendments and halts. It
  is the only code that appends events and changes state.
- **Agents run on virtual threads.** They see the run only through `StageContext`, report back
  with messages, and never touch the stores.
- **Stale results are dropped.** Each attempt carries a dispatch number, so a result that arrives
  after the stage was halted, invalidated or re-dispatched is ignored.
- **Every state change is a transition** in the explicit `StageStatus` table. An illegal one is
  an exception, never a silent corruption.

## Consequences
- **Deterministic event order** for a given sequence of commands. This is what makes the scenario
  fixtures replay event for event, through the CLI and the API alike.
- **No locks.** State is never mutated concurrently.
- **Throughput is one thread per run.** The coordinator does no heavy work (agents and builds run
  elsewhere), so this is not a bottleneck at this scale.
- **Recovery.** The run's state is the projection of its log, so after a crash a new coordinator
  rebuilds it from the events and retries the attempt that was in flight (`RunRecoveryIT`).
