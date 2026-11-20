# Known issues

Issues we know about and have deliberately left in. Each one has an ID that the code references.

## BF-1: Deleted links keep redirecting for a while

| | |
|---|---|
| **Status** | Open, **seeded on purpose** for the brownfield scenario (BUILD_PLAN Step 7.2) |
| **Component** | `shortener-service`: `LinkService.delete()` and the `LinkCache` (L1 Caffeine, L2 Redis) |
| **Severity** | Medium: deleted (and, once expiry exists, expired) links stay reachable |

**Symptom.** After `DELETE /api/v1/links/{code}` returns `204`, `GET /{code}` can still return
`302` to the old destination. It stops when the cache entry expires: up to **5 minutes** while the
entry is in an instance's in-process L1 cache, and up to **24 hours** if it was served from Redis (L2).

**Root cause.** `LinkService.delete()` marks the row `DELETED` in Postgres but never evicts the code
from the cache. Redirects read the cache before the database, so any instance with a cached
`Found` entry keeps redirecting. The code marks the spot with `// KNOWN-ISSUE BF-1`.

**How to reproduce.**
1. Create a link and open `GET /{code}` once, which fills L1 and L2.
2. Delete it with `DELETE /api/v1/links/{code}` and get `204`.
3. `GET /{code}` still returns `302`.

The test `LinkServiceTest.knownIssueBf1_deleteDoesNotEvictCache` pins the current behaviour, so a
fix must update that test.

**Expected fix (out of scope until the brownfield scenario).**
- On delete, evict the code from L2 (Redis) and from the local L1.
- Publish an invalidation message (Redis pub/sub) so **every** instance evicts its L1.
- Accept the residual risk: if a pub/sub message is lost, the L1 TTL (5 min) bounds staleness.
- When link expiry is added, cache TTLs must not outlive the link (`ttl ≤ expiresAt − now`).

**Why it's seeded.** A brownfield demo on unknown code can't be checked. A documented defect lets
reviewers verify that the agents found and fixed the actual root cause (DESIGN_RATIONALE §5).
