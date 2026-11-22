# Setup

Commands are for PowerShell on Windows, from the repository root. On macOS and Linux, use `./mvnw`
instead of `.\mvnw.cmd`.

## Prerequisites
| Tool | Version | Why |
|---|---|---|
| JDK | 21 | both apps and the CLI |
| Docker Desktop | running | Postgres and Redis (Compose), and Testcontainers in the tests and in every validation build |
| Git | 2.4x | the orchestrator clones, branches and merges |
| Maven | not needed | the wrapper (`mvnw`) downloads it |

Ports: the shortener on **8085**, the orchestrator on **8086** (8080/8081 were taken on the
development machine). Postgres is on host port **5434**, Redis on 6379.

## 1. Environment
```
Copy-Item .env.example .env
```
`.env` is git-ignored. Both apps import it, and real environment variables take precedence. Set:

| Variable | Value |
|---|---|
| `SHORTENER_CODE_SECRET` | at least 32 random characters (the Feistel key) |
| `SHORTENER_API_KEYS` | `name:sha256(key)`, comma-separated: who may create links |
| `ORCH_OPERATOR_KEYS` / `ORCH_APPROVER_KEYS` | `name:sha256(key)`: who steers runs, who approves |
| `ORCH_SOURCE_REPO` | a **bare** repository the runs clone and publish to, e.g. `../../shortener-remote.git` (relative to `orchestrator-app/`) |
| `ORCH_LLM_MODE` | `replay` (default) — no API key needed |
| `ORCH_VALIDATION_SANDBOX` | `auto` (default: unreviewed live/record output is built in the Docker sandbox), `docker` (always) or `none` |

To hash a key:
```
$k = "op-demo-key"; -join ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($k)) | % { $_.ToString("x2") })
```
In bash: `printf %s op-demo-key | sha256sum`.

## 2. Infrastructure
```
docker compose up -d                              # Postgres 16 (databases shortener and orchestrator), Redis 7
docker compose --profile observability up -d      # optionally also Prometheus (9090) and Grafana (3000)
```
Stop with `docker compose down`, and add `-v` to delete the data.

## 3. Build
```
.\mvnw.cmd -q install -DskipTests     # build every module (needed before running an app alone)
.\mvnw.cmd -q verify                  # the full build with tests, ~4 min (docs/testing.md)
```

## 4. Run the shortener
```
.\mvnw.cmd -pl shortener-service spring-boot:run
```
Or run it with throw-away Testcontainers Postgres and Redis instead of Compose:
`.\mvnw.cmd -pl shortener-service spring-boot:test-run`.
```
curl.exe -s -X POST http://localhost:8085/api/v1/links -H "X-API-Key: <your key>" -H "Content-Type: application/json" -d '{\"url\":\"https://example.com\"}'
curl.exe -s -i http://localhost:8085/<code>
```
- API docs: http://localhost:8085/swagger-ui.html
- Health: http://localhost:8085/actuator/health

## 5. Run the orchestrator
The runs need a bare repository to clone from and publish to. Create it once:
```
git clone --bare . ..\shortener-remote.git
```
Then start the app:
```
.\mvnw.cmd -pl orchestrator-app spring-boot:run
```
- The dashboard is at http://localhost:8086: enter an operator or approver key at the top.
- The API is under `/api` (Swagger at http://localhost:8086/swagger-ui.html).

## 6. Run the scenarios (replay mode, no API key)
**From the CLI**, all in one process with scripted humans. No app and no database are needed, only
Docker for the validation build:
```
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario greenfield-qr-code"
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario brownfield-expiry-bf1"
.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario ambiguous-abuse"
```
Each prints its events as they happen and ends with the status and the audit result. The log is
saved to `.orchestrator/scenarios/<id>/events.jsonl`. The write-ups are in `docs/scenarios/`.

**From the dashboard:** start a run with the scenario id and play the humans yourself. The source
repository's `main` must be at the scenario's base (`phase-7`, see `docs/testing.md`):
```
git -C ..\shortener-remote.git update-ref refs/heads/main phase-7
```
- Answers and amendments go through the API, so the text matches the fixtures exactly:
  ```
  curl.exe -s -X POST http://localhost:8086/api/runs/<runId>/answers -H "X-API-Key: <operator key>" -H "Content-Type: application/json" --data-binary "@scenarios/ambiguous-abuse/answers.json"
  ```
- The amendment body for 7.3 is `{"text": ...}`, with the text from `scenarios/ambiguous-abuse/scenario.yml`.

**As tests:** `.\mvnw.cmd clean verify -Pe2e` runs all three through the API on Postgres, plus the
fan-out scenario `greenfield-fanout-notes`. Run it as written. Adding `-Dit.test=…` makes both
failsafe executions of the profile run the named class, so the scenarios run twice. To run one
scenario, use `run-scenario <id>` (above).

## 7. Live and record mode
These modes cost API credit and are slow: implementation and test replies take minutes.
- **The key:** put `ANTHROPIC_API_KEY` in `.env`, never in a chat or a commit.
- **The model:** `ORCH_LLM_MODEL` defaults to `claude-sonnet-5-5`. `ORCH_LLM_FALLBACK_MODEL` is
  optional.
- **Record a scenario's fixtures:**
  `.\mvnw.cmd -q -pl orchestrator-cli exec:java "-Dexec.args=run-scenario <id> --llm record"`.
  Files in `scenarios/<id>/replies/` take precedence over the model.
- **Without a key:** `record-fixtures <id>` writes each missing prompt to `replies/pending/` for
  you to answer by hand.
- **Live runs in the app:** set `ORCH_LLM_MODE=live`. Model-written code then waits for approval
  (CHG-005), and validation builds it in the Docker sandbox (`docs/security.md`, O-1).
- **The sandbox:** the first sandboxed build downloads the Maven cache (volume
  `orchestrator-sandbox-m2`) and the test images, which takes several minutes. Later builds take
  about 1.5 minutes longer than on the host. Try it on a replay:
  `run-scenario greenfield-qr-code --sandbox docker`.

## Troubleshooting
| Symptom | Cause and fix |
|---|---|
| `ReplayMissingException … DesignAgent` | A prompt input changed: shortener code at the base, a prompt, a schema. Check `scenario.yml` says `base: phase-7` and the source repository is at that commit. The CLI saves the request under `.orchestrator/scenarios/<id>/replay-missing/` to compare. |
| `credit balance is too low` with a key in `.env` | Another `ANTHROPIC_API_KEY` is set in the Windows environment and takes precedence. Remove it (Edit environment variables for your account) and restart the terminal. |
| `Run not found` from the API | Run ids look like `run-71d879f9048d`, with the `run-` prefix. |
| 401 / 403 from the API | No key, an unknown key, or the wrong role: decisions need an approver key, starting runs and answers an operator key. |
| `publish` refuses to merge | `ORCH_SOURCE_REPO` points at a working copy. Use a bare clone. |
| `-Pe2e` fails right after a failed run | failsafe merged the old summary. Run with `clean`. |
| `-Pe2e` runs the scenarios twice (about 20 min) | `-Dit.test=…` was added: it replaces the includes of both failsafe executions. Run `clean verify -Pe2e` without it, or `run-scenario <id>` for one scenario. |
| Port 5434 / 8085 / 8086 in use | Change it in `.env` (`POSTGRES_PORT`) or with `--server.port`. |
| Tests hang or fail to start containers | Start Docker Desktop. Testcontainers needs it for every `*IT`. |
| A long `curl` command fails in PowerShell | It wrapped when pasted. Paste it on one line, and use `curl.exe`, not the `curl` alias. |
