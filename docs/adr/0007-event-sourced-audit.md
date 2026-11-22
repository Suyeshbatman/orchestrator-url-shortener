# ADR-0007: The append-only, hash-chained event log is the source of truth and the audit

- **Status:** Accepted
- **Date:** 2026-11-21
- **Scope:** `orchestrator-core` (`event`), `orchestrator-app` (`persistence`)

## Context
An SDLC orchestrator that changes code on its own must be auditable. Every reviewer needs to know
who decided what, when, why and from which inputs. The run must also survive crashes.

## Decision
- **Event sourcing.** Every change to a run is an event: 25 types, from `RUN_CREATED` through
  `POLICY_EVALUATED`, `APPROVAL_*` and `REPLAN_TRIGGERED` to `RUN_COMPLETED`. Run and stage state
  are projections of the log. The tables `run`, `stage_instance` and `approval` are kept in step
  with it for queries.
- **What an event records.** The actor (`agent:DesignAgent`, `human:alice` or `system`), the stage,
  a payload with the reasons (gate results, policy matches, approval comments) and references to
  the artifacts it used.
- **Hash chain.** `hash_n = SHA-256(hash_{n-1} ‖ canonical(event_n))`, over canonical JSON (sorted
  keys, fixed number formats). `GET /api/runs/{id}/audit/verify` recomputes it and names the first
  broken event.
- **Append-only in the database.** Triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on
  `workflow_event`.
- **Artifacts are versioned and never overwritten.** Each records its inputs, producer, model and
  prompt hash, so lineage can be queried (`GET /api/artifacts/{id}/lineage`).

## Consequences
- **Recovery is a replay of the log** (ADR-0006).
- **The audit is complete by construction.** Nothing changes without an event.
- **Tampering is evident, not impossible.** A database superuser could rewrite the whole chain.
  In production, anchor the head hash periodically to write-once storage.
- **Canonical serialization matters everywhere.** The validation report is canonical JSON for the
  same reason: it feeds later prompts, and its replay hash must not depend on map order.
