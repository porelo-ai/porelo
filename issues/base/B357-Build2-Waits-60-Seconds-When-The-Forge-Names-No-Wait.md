# B357 — Build2 Waits 60 Seconds When The Forge Names No Wait

**Status:** Idea.
**Size:** S, proposed - one default in the `Build2` interface's `RateLimited` and the adapters that answer it.

**What must be true.** When a forge limits how often it is asked and names no wait, `Build2.RateLimited` answers
`retryAfter: 60`, not 0, as `Review1` does. A caller that retries at once on 0 asks a limited forge again at once, and
is limited again. Michi's decision of 2026-10-31 (operator): `Review1` answers 60 seconds, as the forge suite rule
does, and `Build2` follows.

## Acceptance

- A rate limit without `Retry-After` is answered `RateLimited` with `retryAfter: 60` by `Build2`, as by `Review1`.
- A named wait is answered as the forge named it.
- The interface's comment in `org.fuin.porelo.Build2.varlink` says 60, not 0.
- Seen to fail first: today `Build2` answers 0 when the forge did not say.
