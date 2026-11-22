# Scenario 7.3: an ambiguous requirement, answered and then amended

> "Make the shortener safer — people are abusing it."

## Run it
From the repository root (Docker Desktop running):
```
.\mvnw.cmd -q install -DskipTests
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario ambiguous-abuse"
```
It replays `scenarios/ambiguous-abuse/replay/` (no API key). The scripted humans answer from
`answers.json` and amend the requirement as `scenario.yml` says. The run ends `COMPLETED` (205 events,
about 3 minutes). Its log is `.orchestrator/scenarios/ambiguous-abuse/events.jsonl`.

## What the orchestrator did
| # | Event | What happened |
|---|---|---|
| 7 | **spec v1** | Four **BLOCKING** questions: which abuse (malicious URLs, flooding or spam accounts); block or flag; which threat lists (an external API or our own); rescan existing links? |
| 10 | run **AWAITING_HUMAN** | `spec.noBlockingQuestions` failed, so the stage waits for input, not for approval |
| 11 | **answers v1** | From `answers.json`: malicious destinations; block at creation from our own domain list; an admin can flag a link, which then answers 451; no external API; no rescan |
| 12 | re-plan | "answers to the open questions were given": requirements re-run |
| 19 | **spec v2** | 4 FR, 2 NFR, 7 AC, no open questions → scope sign-off |
| 20–48 | plan | 5 tasks; impact analysis: 14 files and migration V3; design approved |
| 49–86 | build (v2) | implementation, tests and docs run in parallel; the implementation waits for **CHG-001** (the migration) |
| 87 | **amendment** | the operator adds "Also block URL shorteners chaining to other shorteners" (requirement v2) |
| 88–95 | **re-plan** | requirements and every stage after them are invalidated; the waiting implementation approval and the **design approval are voided** |
| 101 | **spec v3** | FR-2 (refuse other shorteners) is added; scope sign-off again |
| 102–181 | re-execution | new tasks, impact and design (approved again), then implementation, tests and docs; validation: build succeeded, **277 tests green, 95 % line coverage**; CHG-001 approved |
| 182–205 | release | final docs, go / no-go, merge into the run's `main` |

**Lineage of the requirement.** In time order: spec v1 → answers v1 → spec v2 → requirement v2 (the
amendment) → spec v3. Each artifact records what it was derived from, so the dashboard's lineage
view of spec v3 (checked in a run started from the dashboard, which matched this one event for
event) shows:
```
requirement v1 ──► spec v1 ──► answers v1 ──┐
       │                                    ├──► spec v3
       └──────► requirement v2 (amended) ───┘
```
Spec v2 is a sibling, not an ancestor: after the amendment the requirements agent read the amended
requirement and the answers, not the superseded spec. Its own lineage is requirement v1 + answers v1.

## The change
- **Refused destinations** (`UrlValidator`): hosts on `shortener.abuse.blocked-domains` or other URL
  shorteners (`shortener.abuse.shortener-domains`, the common public ones by default) are refused,
  each with all its subdomains. The answer is `400 invalid-url` with reason `BLOCKED_DOMAIN` or
  `URL_SHORTENER`. No network call.
- **Flagging:** `POST /api/v1/admin/links/{code}/flag` for keys in `shortener.abuse.admin-keys`
  (403 otherwise). Flyway V3 adds status `FLAGGED`. A flagged link answers `451 link-unavailable`
  from the next request on and is never cached.

## Lessons
- **Asking beats guessing.** The four questions lead to very different systems (a URL filter, a
  rate limiter, account moderation). Stopping cost one round trip; guessing would have cost a
  rebuild.
- **An amendment is a re-plan, not a patch.** Every stage that read the old requirement was
  invalidated. The approvals given for it were voided too, because they approved a different scope.
- **Replay needs a fixed order.** The amendment lands when the run is waiting for a human, and that
  happens only once no stage is running. So the agent calls happen in the same order on every
  replay.
