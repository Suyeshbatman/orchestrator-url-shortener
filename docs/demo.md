# Demo

## The automatic demo (about 8 minutes, no API key)
From the repository root, with Docker Desktop running and nothing else on port 8086. A fresh
clone needs no `.env`:
```
.\mvnw.cmd -q install -DskipTests
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=demo"
```
It runs these steps, each under its own banner:
1. **A Postgres of its own**: compose project `orchestrator-demo` on port **5435** with a
   demo-only password, and a fresh database `orchestrator_demo`. It needs no `.env` and never
   touches your containers, ports or data. Remove it with `docker compose -p orchestrator-demo down -v`.
2. **A bare copy of this repository** with `main` at `phase-7`, the code the scenario fixtures were
   recorded against. The runs clone from it and publish into it.
3. **The orchestrator app on 8086**, in replay mode, with operator and approver keys made up for
   this run.
4. **The three scenarios through the HTTP API.** bob (operator) and alice (approver) follow each
   `scenario.yml`. Every approval is printed with the reason it waits, its summary and its risk
   before alice decides. 7.2's design is rejected once, and 7.3 is answered and then amended.
   Progress prints as it happens: stages, loop-backs, the fallback, re-plans.
5. **The audit check of each run, then the metrics summary** from `/api/metrics/summary`.

It ends with `OK: all three scenarios completed and their audit chains verify` (exit code 0). The
app's log and the runs' workspaces are in `.orchestrator/demo/`.

The CLI also has `verify-audit <runId> --key <key>` and `metrics --key <key>` for any running
orchestrator. The plan's `scripts/demo.sh` is this CLI command, as agreed: one cross-platform
implementation instead of a bash script and `jq`.

## The 5-minute manual walkthrough (the dashboard)
Prepare once (`docs/setup.md` §1 and §5): `.env` with operator key `op-demo-key` and approver key
`ap-demo-key` (hashed), `ORCH_SOURCE_REPO` set to a bare clone at `phase-7`, `docker compose up -d`,
and the app running:
```
git -C ..\shortener-remote.git update-ref refs/heads/main phase-7
.\mvnw.cmd -pl orchestrator-app spring-boot:run
```

| Minute | Do | Point out |
|---|---|---|
| 0:00 | Open http://localhost:8086, enter `op-demo-key`, start scenario `ambiguous-abuse` (BROWNFIELD, L1) | The DAG: the stages, the parallel branch, the joins, which stages need a human |
| 0:30 | Watch `requirements` stop | It isn't an approval but **4 blocking questions**. The run is `AWAITING_HUMAN` because guessing would build the wrong system |
| 1:00 | Send the answers (`docs/setup.md` §6: `curl.exe … /answers --data-binary "@scenarios/ambiguous-abuse/answers.json"`) | `REPLAN_TRIGGERED`: requirements re-run with the answers and spec v2 appears |
| 1:30 | Switch the key to `ap-demo-key`. Approve scope, then design | The approval shows the summary and the risk score. Only an approver key can decide |
| 2:30 | When `implementation` waits for CHG-001 (the migration), **don't approve**: send the amendment (`curl.exe … /requirements --data-binary "@.orchestrator/amend-ambiguous-abuse.json"`) | The re-plan invalidates 7 stages and **voids** the pending approval and the design approval, because they approved a different scope |
| 3:00 | Approve again: scope, design, implementation, then validation after the build (about a minute) | Validation is a real Maven build: 277 tests, 95 % coverage, the policy scan of the diff |
| 4:00 | Approve go/no-go and publish | The release checklist and its evidence; the merge runs only after approval |
| 4:30 | Open the final `requirements_spec` and its **lineage**; run the audit check | requirement v1 → spec v1 → answers → (amended requirement) → spec v3; the hash chain verifies. Metrics are on the dashboard |

Make `.orchestrator/amend-ambiguous-abuse.json` once with the amendment text from
`scenarios/ambiguous-abuse/scenario.yml`:
```
{"text": "Make the shortener safer — people are abusing it. Also block URL shorteners chaining to other shorteners."}
```
Save it as UTF-8: the em dash must match the fixture byte for byte.
