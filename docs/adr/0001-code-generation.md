# ADR-0001: Short codes from a sequence, a keyed Feistel permutation and Base62

- **Status:** Accepted
- **Date:** 2026-11-20
- **Scope:** `shortener-service`, package `code`

## Context
Every link needs a short code with these properties:
- **Unique:** no collisions, ever (NFR-5).
- **Not enumerable:** knowing one code must not reveal its neighbours or the total volume.
- **Short:** 7 characters.
- **Cheap:** O(1) to generate, with no coordination between instances beyond the database.

## Options considered
| Option | Collisions | Enumerable | Verdict |
|---|---|---|---|
| Truncated hash of the URL | **Yes.** Around 1.9 M links give 50 % odds of a collision (birthday bound on 62⁷) | No | Needs a retry loop. Rejected |
| Random 7 characters, retry on a unique-key violation | Rare, rising as the space fills | No | Works, but latency is unpredictable |
| Sequence → Base62 | None | **Yes** (`abc0001`, `abc0002`, …) | Leaks volume and invites scraping. Rejected |
| Snowflake IDs | None | Partly (time-ordered) | 64 bits is about 11 Base62 characters, too long |
| **Sequence → keyed Feistel → Base62** | **None** (a bijection) | **No** without the key | **Chosen** |

## Decision
1. Take the next value of the Postgres sequence `link_id_seq` before inserting.
2. Permute it with a 4-round Feistel network on 42 bits.
   - The round function is truncated HMAC-SHA256, keyed with `SHORTENER_CODE_SECRET` (32+ characters).
   - A Feistel network is invertible whatever its round function is, so the mapping is a bijection:
     two ids can never produce the same code.
3. **Cycle-walk** into range: 2⁴² is a little larger than 62⁷ ≈ 3.52 × 10¹². While the result is at
   least 62⁷, permute it again. On average this costs fewer than 0.25 extra rounds.
4. Base62-encode the result (`[0-9a-zA-Z]`), left-padded to 7 characters.

**Custom aliases** share the `code` column, with `custom_alias = true`.
- A partial unique index on `lower(code)` makes aliases unique case-insensitively, and it decides
  races: the losing request gets `409`.
- Reserved words (`api`, `actuator`, …) are refused, so aliases can't shadow routes.
- Before inserting a generated code, the service checks that no alias already uses it, and takes the
  next id if one does.

## Consequences
- ✅ No collision handling for generated codes, and generation costs one sequence call.
- ✅ Codes look random. 62⁷ codes last about 35,000 years at 100 M links per year.
- ✅ Property tests check that Base62 round-trips and that the Feistel permutation is a bijection,
  including an exhaustive check on a small domain.
- ⚠️ **This resists enumeration but is not access control.** Anyone holding a short link can share it.
- ⚠️ The secret must stay secret. Rotating it changes codes for **new** links only; existing codes
  are stored, not recomputed.
- ⚠️ This is obfuscation, not encryption. Four rounds scramble well, but they are not a cipher.
