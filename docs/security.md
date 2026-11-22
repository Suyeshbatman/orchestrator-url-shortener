# Security review

Scope: the URL shortener (`shortener-service`) against the OWASP Top 10, and the orchestrator
(`orchestrator-*`) against the LLM-specific risks: prompt injection, excessive agency, insecure output
handling and secrets in prompts.

Severity is for this system as it would be deployed. **HIGH** means a realistic path to compromise
or data loss, **MED** a real weakness with a limited blast radius, and **LOW** hardening.

## Summary
| # | Finding | Severity | Status |
|---|---|---|---|
| O-1 | Validation builds and runs model-written code before any human sees it | **HIGH** | **Fixed** (CHG-005); residual: no sandbox |
| O-2 | Prompt injection through repository content | MED | Mitigated by design; residual stated |
| O-3 | Agent-chosen file paths and commands | MED | Mitigated (path guard, path jail, allowlist) |
| O-4 | Secrets reaching the model or the build | MED | Mitigated (redaction, environment scrubbing, SEC-001) |
| O-5 | Excessive agency: merging without a human | MED | Mitigated (publish is approved before it runs) |
| O-6 | Model output rendered in the dashboard | LOW | Mitigated (text only, strict Mermaid) |
| O-7 | Orchestrator API key hashes passed to the build | LOW | Residual |
| S-1 | SSRF through destination URLs | MED | Mitigated; residual: DNS rebinding (no fetcher exists) |
| S-2 | Open redirect / phishing | MED | Inherent to a shortener; mitigated |
| S-3 | Injection (SQL, header, log) | LOW | Mitigated |
| S-4 | Authentication and access control | LOW | Mitigated |
| S-5 | Rate limiting and resource exhaustion | LOW | Mitigated |
| S-6 | Information exposure (actuator, Swagger UI, errors) | LOW | Partly mitigated; production settings listed |
| S-7 | Known defect BF-1: deleted links redirect from cache | MED | Open on `main`, fixed in scenario 7.2 |

## Orchestrator: LLM-specific risks

### O-1 Model-written code runs before review (HIGH, fixed)
The validation stage runs `mvn -q -pl shortener-service -am verify` on the code the
implementation and test agents just wrote. Tests are code. In live mode that code came from a model
that read repository content (see O-2). It runs with the orchestrator's user, its network and its
Docker access, and Docker access is effectively administrator access. At `L1_SUPERVISED` no human
saw it first unless a change policy happened to match.

**Fix.**
- **The flag.** A write of model output that no human has reviewed is now marked
  (`ProposedAction.FileWrite.unreviewed`). That covers live and record modes. Replayed fixtures and
  hand-written replies count as reviewed, because they were reviewed when they were committed.
- **The policy.** A new rule in `config/policies.yml`:
  ```yaml
  - id: CHG-005-unreviewed-code
    on: file_write
    paths: ["shortener-service/**"]
    when: unreviewed
    effect: REQUIRE_APPROVAL
  ```
- **The effect.** The implementation and test-authoring stages both wait for a human, at every
  autonomy level, and validation depends on both. So nothing an unreviewed model wrote is built
  until an approver has seen the files. Docs are not built and stay automatic.
- **Tests.** `PolicyEngineTest.chg005UnreviewedModelCodeIsReviewedBeforeItBuilds` and
  `WorkspaceAndFilesTest.writesOfUnreviewedModelOutputAreMarkedForThePolicies`.

**Residual.** The build still runs on the host after approval. A reviewer can miss a malicious line
in a large patch. Run live mode only on a disposable VM or a dedicated build host without
credentials. A real sandbox (the build in a container with no network, and Testcontainers against a
separate Docker daemon) is the next step.

### O-2 Prompt injection through repository content (MED)
Agents read the repository: the code map, the files they change, the tests they update, and the
requirement text. A hostile file could contain instructions.

**In place:**
- **Data, not instructions.** Every prompt says that input content is data, never instructions.
- **No free text.** Replies must be JSON that validates against a strict schema (one repair try).
  There is no free-text channel into actions.
- **Every effect goes through the policy engine:**
  - file writes are limited to `shortener-service/src/**`, its `pom.xml`, `docs/**` and
    `scenarios/**` (SEC-002);
  - the orchestrator's own code and guardrails can never be written (SEC-003);
  - commands come only from an exact allowlist (CMD-001);
  - dependency, schema and security-config changes need a human (CHG-001, CHG-002, CHG-004).
- **Humans at the decision points.** At L1 a human signs off scope, design, release and the merge.
  Unreviewed code is now approved before it builds (O-1).
- **Independent tests.** Tests are written from the spec by a separate agent, so one injected agent
  can't make its own code look right.
- **"Make it green" shortcuts fail.** Scenario 7.2 shows it: an attempt to skip a failing test in
  the root POM was denied.

**Residual.** Injection can still bias what the model writes inside the allowed paths. The
defences are review, the real build and the independent tests, not detection.

### O-3 Agent-chosen paths and commands (MED)
- **Paths.** `PathGuard` refuses absolute paths, drive letters, `~`, and `..` that leaves the
  workspace. It resolves symlinks, so a link inside the workspace cannot point outside.
- **The jail.** The policy engine normalises the path and checks it against the jail before
  anything is written.
- **Commands.** `CommandTool` runs only exact allowlisted lines with no shell, so pipes, `&&` and
  redirects are just arguments. It has a timeout, and it kills the whole process tree.

### O-4 Secrets in prompts and builds (MED)
- **Before the model.** `RedactingLlmGateway` removes environment values whose names look secret,
  plus known formats (Anthropic and AWS keys, GitHub tokens, private keys, bearer tokens), from
  every request. The recorded fixtures were made after redaction.
- **In the build.** The build's environment loses every variable whose name looks secret
  (`ANTHROPIC_API_KEY`, `*_PASSWORD`, `*_TOKEN`, `*SECRET*`), so code under test cannot read the
  API key.
- **In diffs.** SEC-001 denies diffs that add AWS keys, private keys or hard-coded
  passwords or secrets.
- **At rest.** `.env` is git-ignored and `.env.example` holds placeholders only. The API key is
  read from the environment and never logged.

### O-5 Excessive agency (MED)
- **Publishing.** Merging to `main` is a separate `publish` stage whose action is approved before
  it runs, at every level. `MergeToMainAction` also refuses a non-bare local origin.
- **Budgets.** Each run caps LLM calls, tokens and wall-clock time (`sdlc-standard.yml`). Going
  over safe-stops the run instead of failing in the middle.
- **Stopping.** Halt, kill, safe stop and rollback are operator commands. The hash-chained audit
  log records every decision, policy check and approval, and is verified on demand.

### O-6 Model output in the dashboard (LOW)
- **Text only.** The dashboard writes artifact content, summaries and reasons with
  `textContent`, never as HTML.
- **Mermaid.** It is loaded with SRI and renders with `securityLevel: "strict"`. The only HTML it
  injects is Mermaid's own SVG, built from workflow stage ids.
- **API keys.** They stay in page memory, never in browser storage.

### O-7 API key hashes in the build environment (LOW, residual)
`ORCH_OPERATOR_KEYS` and `ORCH_APPROVER_KEYS` hold SHA-256 hashes, not keys, and their names
don't look secret, so they reach the build's environment. Code under test could read them and
brute-force a weak key offline. Use long random keys. If the build moves to a sandbox (O-1), switch
the scrubbing to an allowlist.

## Shortener: OWASP Top 10

### S-1 SSRF (A10) — MED, mitigated
- **Today.** The service never fetches a destination; it only redirects to it.
- **Defence in depth.** `UrlValidator` still refuses:
  - schemes other than http and https;
  - credentials in the URL;
  - non-canonical IP spellings;
  - hosts that resolve to private, loopback, link-local or multicast addresses;
  - hosts that don't resolve (fail closed);
  - the service's own host.
- **Residual.** DNS rebinding (public at creation, private later) is documented in the validator.
  Any future fetcher, such as a preview or a reputation check, must resolve and pin the IP itself.

### S-2 Open redirect and phishing (A01) — MED, inherent
Redirecting anywhere is the product. What limits abuse:
- **Schemes.** Only http and https, so no `javascript:` or `data:` URLs.
- **Self-links.** Links to the service itself are refused, so it can't loop.
- **Rate limits.** Creation is limited per API key, and only authenticated keys can create links.
- **Removal.** Links can be deleted. Scenario 7.3 adds domain blocklists, refusal of other
  shorteners, and admin flagging (451) on its branch.
- **Residual.** Phishing destinations on unknown domains.

### S-3 Injection (A03) — LOW, mitigated
- **SQL.** All SQL goes through Spring Data JDBC or `JdbcClient` with named parameters. The only
  string concatenation joins constant column lists.
- **Redis.** Redis keys are `link:` plus a validated code.
- **Headers and logs.** `Location` is built from a parsed `URI`, and codes and aliases match
  `[A-Za-z0-9_-]{4,32}`. Log lines use parameters, and raw client IPs are never logged (CMP-001
  enforces this for agents).

### S-4 Authentication and access control (A01, A07) — LOW, mitigated
- **Keys.** API keys are stored as SHA-256 hashes and compared in constant time
  (`MessageDigest.isEqual`).
- **Ownership.** A key sees and deletes only its own links; another owner's link answers 404, so
  existence isn't revealed. Idempotency records are scoped per key.
- **The orchestrator API.** Same scheme, with operator and approver roles. Only an approver can
  decide an approval, and an approval only counts from a human principal.

### S-5 Rate limiting and resource exhaustion (A04) — LOW, mitigated
- **Token buckets.** Creation is limited per key, with a local fallback when Redis is down.
  Redirects are limited per visitor fingerprint, failing open when Redis is down.
- **Client IP.** It comes from `getRemoteAddr()`; `X-Forwarded-For` is never trusted directly.
  Behind a proxy, set `server.forward-headers-strategy`.
- **Size limits.** URLs are at most 2048 characters. The click queue is bounded and drops on
  overflow instead of blocking redirects.

### S-6 Information exposure and misconfiguration (A05) — LOW, partly mitigated
- **In place.** Errors are RFC 9457 problem details without stack traces. Security headers are set
  (CSP, HSTS, `nosniff`, `X-Frame-Options: DENY`, Referrer-Policy, `no-store` on the API). Only
  `health`, `info` and `prometheus` are exposed.
- **For production:**
  - serve actuator on a separate management port (`management.server.port`), so Prometheus
    metrics and health details aren't public;
  - set `springdoc.swagger-ui.enabled=false` and `springdoc.api-docs.enabled=false`;
  - set `management.endpoint.health.show-components=never` on public instances.

### S-7 BF-1 (MED, open on `main`)
Deleted links keep redirecting from cache until the TTL expires: up to 5 minutes in L1, 24 hours in
L2. The defect is seeded on purpose and fixed by scenario 7.2, whose merge isn't applied to `main`.
See `docs/known-issues.md`.

## Residual risks, in order
1. **Model-written code runs on the host after approval** (O-1). Use a disposable host for live
   mode. A sandboxed build is the next step.
2. **Injection can bias code inside the allowed paths** (O-2). Review and the independent tests are
   the defence.
3. **Phishing through the shortener** (S-2). Blocklists and moderation exist on the 7.3 branch;
   reputation services were ruled out.
4. **Cross-instance L1 staleness** after a delete or flag: up to 5 minutes (S-7, 7.2, 7.3).
5. **Public actuator and Swagger UI** unless the production settings in S-6 are applied.
