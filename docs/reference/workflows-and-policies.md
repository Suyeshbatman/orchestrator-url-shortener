# Workflows, policies and autonomy

Three YAML files under `config/` define what a run does and what it may do.
- **When they're loaded:** at run start, by `WorkflowLoader` and `PolicyLoader`.
- **Validation is strict:** an unknown key, a wrong type, an unknown gate, a cycle or a bad regex is
  an error that names its location. Every problem is reported at once, and the run doesn't start.
- **Audit:** the files' hashes go into `RUN_CREATED`, so a run's rules are part of its record.

Agents can never change these files: SEC-003 denies writes to `config/policies.yml`,
`config/autonomy.yml` and `orchestrator-*/**`.

## Workflow (`config/workflows/<name>.yml`)
```yaml
workflow: sdlc-standard          # id, required
version: 1
budgets: { maxWallClock: 60m, maxLlmCalls: 60, maxTokens: 1000000 }
defaults:
  retry: { maxAttempts: 2, backoff: exponential, baseDelay: 2s, maxDelay: 20s, jitter: true }
  timeout: 5m
stages:
  - id: design
    agent: DesignAgent
    dependsOn: [decomposition, impact_analysis]
    exitGates: [artifact.schemaValid:design_doc, design.apiContractPresent]
    approval: { required: true, reason: "Architecture sign-off" }
```

### Top level
| Key | | |
|---|---|---|
| `workflow` | required | the id |
| `version` | default 1 | |
| `budgets.maxWallClock` / `maxLlmCalls` / `maxTokens` | | Exceeding any one safe-stops the run (`BUDGET_EXCEEDED`, then `RUN_HALTED`). It doesn't fail it |
| `defaults.retry`, `defaults.timeout` | timeout default `5m` | apply to every stage that doesn't set its own |
| `stages` | at least one | |

### A stage
| Key | | |
|---|---|---|
| `id` | required | `[a-z][a-z0-9_]*`, unique |
| `agent` **or** `action` | exactly one | `agent`: a name in the agent registry (`RequirementsAgent`, …). `action`: a deterministic action, e.g. `git.mergeToMain` |
| `dependsOn` | list of stage ids | edges of the DAG. A cycle is a load error |
| `condition` | e.g. `run.mode == 'BROWNFIELD'` | fields `run.mode`, `run.autonomyLevel`; operators `==` and `!=`. If false the stage is `SKIPPED`, which satisfies its dependents |
| `entryGates` / `exitGates` | gate names | see below. An entry failure fails the attempt before the agent runs; an exit failure is a validation failure (retry, loop-back or fallback) |
| `approval` | `{required, reason, timeout}` | a human must approve. Without a timeout it waits forever. A timeout that passes safe-stops the run |
| `retry` | `{maxAttempts 1–10, backoff fixed|exponential, baseDelay, maxDelay, jitter}` | see "failure handling" |
| `timeout` | duration | per attempt. Running out is a transient failure |
| `fallback` | `{strategy}` | one narrower attempt after the retries (or straight away after a policy DENY). The agent sees the strategy, e.g. `minimal-patch` = exactly one file |
| `onFailure` | `{loopTo, maxLoops, feedback}` | on a validation failure, invalidate `loopTo` and everything between it and this stage, and re-run them with this stage's `feedback` artifact |
| `checkpoint` | `git` | commit the workspace before the stage, for rollback and the clean retry of a failed attempt |
| `expand` | `tasks` | **parsed and validated, not applied yet:** the coordinator runs the stage once over the whole task graph. `Dag.expand` (one sub-stage per task, keeping their dependencies) is implemented and tested, but not wired in |
| `highImpact` | `true` | if the stage fails, the run halts for a human instead of failing |

Durations: `\d+(ms|s|m|h|d)`.

### Gates (`GateRegistry.standard`)
Gates are deterministic code; no gate asks a model.

| Gate | Passes when |
|---|---|
| `input.nonEmpty` | the requirement text isn't empty |
| `artifact.schemaValid:<type>` | this attempt produced `<type>` and it validates against `schemas/<type>.json` |
| `spec.noBlockingQuestions` | no ambiguity in the spec is `BLOCKING`. Otherwise the stage waits for answers (`needsInput`) instead of failing |
| `taskGraph.acyclic` | the tasks' `dependsOn` form a DAG |
| `taskGraph.everyRequirementCovered` | every FR and NFR id appears on at least one task |
| `design.apiContractPresent` | `apiChanges` is non-empty |
| `build.green` | the validation build succeeded |
| `tests.green` | 0 failed, 0 errors, at least one test |
| `coverage.min:<0..1>` | line coverage is at least the argument |
| `policy.noDeny` | the policy verdict on the whole diff isn't DENY |
| `release.checklistComplete` | every checklist item is `DONE` or `NOT_APPLICABLE` |

## Policies (`config/policies.yml`)
```yaml
policies:
  - id: SEC-002-path-jail
    on: file_write
    allow_paths: ["shortener-service/src/**", "shortener-service/pom.xml", "docs/**", "scenarios/**"]
    effect_outside: DENY
```
**How rules combine:**
- **The effect** of an action is the strictest of every rule that matches. Any `DENY` wins, then
  any `REQUIRE_APPROVAL`, else `ALLOW`. No rule and no autonomy level can lower a DENY.
- **Every match is reported** with its id and reason. The reasons appear in the events and in the
  approval the human sees.
- **Risk score:** the sum of the matched rules' `weight` plus a size factor, capped at 100.
  - Weights default to DENY 50 and REQUIRE_APPROVAL 20, settable 0–100.
  - Size factor: a diff adds up to 30 (lines / 40 + files), a file write up to 10, a publish 30.

| `on` | Kind | Keys | Matches |
|---|---|---|---|
| `file_write` | jail | `allow_paths`, `effect_outside` | a write outside every allowed glob |
| `file_write` | path rule | `paths`, `effect`, optional `when: unreviewed` | a write to a matching path. `when: unreviewed` restricts it to unreviewed model output |
| `diff` | patterns | `deny_patterns`, `effect` | an **added** line matching a regex |
| `diff` | size | `max_files_changed` and/or `max_lines_changed`, `effect` | a change set larger than that |
| `command` | allowlist | `allow`, `effect_outside` | a command line not exactly on the list. Commands run without a shell |

Every rule may also have `message` (shown with the match) and `weight`.
- **Globs:** paths are `/`-separated and repo-relative. `**` matches any number of segments, none
  included, so `**/pom.xml` matches `pom.xml`. `*` and `?` stay within one segment.
- **Diffs are checked against the `file_write` rules too,** for every path they touch, old and new,
  so a delete is caught as well.

**The rules in use:**

| Id | Effect | Rule |
|---|---|---|
| SEC-001-no-secrets | DENY | an added AWS key, private key, or a hard-coded password, secret or API key in a diff |
| SEC-002-path-jail | DENY | writes outside `shortener-service/src/**`, `shortener-service/pom.xml`, `docs/**`, `scenarios/**` |
| SEC-003-forbidden-paths | DENY | `.github/**`, the policy and autonomy files, the orchestrator's own code |
| CHG-001-schema-migration | approval | `**/db/migration/**` |
| CHG-002-dependency-change | approval | `**/pom.xml` |
| CHG-003-blast-radius | approval | more than 25 files or 1200 lines |
| CHG-005-unreviewed-code | approval | unreviewed model output under `shortener-service/**` (it gets built and run) |
| CHG-004-security-config | approval | `**/config/Security*.java`, `**/application*.yml(.yaml)` |
| CMP-001-pii | DENY | a log statement that logs a client IP |
| CMD-001-command-allowlist | DENY | anything but `mvn -q -pl shortener-service -am verify` and `mvn -q -pl shortener-service test` |

## Autonomy (`config/autonomy.yml`)
| Level | |
|---|---|
| `L0_SUGGEST` | every file write needs approval |
| `L1_SUPERVISED` (default) | writes inside the jail are allowed; REQUIRE_APPROVAL policies and stage approvals apply |
| `L2_TRUSTED` | as L1, but the design approval is skipped when the design's risk score is below 30 |

**Invariants**, enforced in code (`policy.Invariant`); the file only documents them:
- publishing requires human approval;
- a DENY can never be overridden;
- an approval counts only from a human principal with the `APPROVER` role.

The loader refuses an autonomy file whose levels or invariants differ from the code's.

## Failure handling, in order (`Coordinator.failAttempt`)
1. **A validation failure** (an exit gate failed, or the reply was invalid) of a stage with
   `onFailure` loops back while loops remain.
2. **A transient or validation failure** is retried while `maxAttempts` remain. The delay is
   `min(maxDelay, baseDelay × 2^(attempt−1))` for exponential backoff; with `jitter`, a uniformly
   random duration between 0 and that ("full jitter").
3. **A policy DENY** (`DENIED`) is never retried as it was. It goes to the fallback.
4. **The fallback** runs once per loop, with the reason as feedback.
5. **Otherwise the stage fails,** and the run fails. A `highImpact` stage halts the run instead. A
   failed attempt that wrote files is restored to its checkpoint first.

Failure kinds (`FailureKind`):

| Kind | Raised by |
|---|---|
| `TRANSIENT` | timeouts, I/O, LLM unavailable |
| `VALIDATION` | gates, schema |
| `DENIED` | any check in the attempt said DENY |
| `FATAL` | illegal state or input, or a rejection of an approval before the stage runs |
| `BUDGET` | a budget ran out: the run is safe-stopped, not failed |
