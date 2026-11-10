# B357 — Build2 Waits 60 Seconds When The Forge Names No Wait

```json
{
  "$schema": "issue-1.json",
  "id": "B357",
  "title": "Build2 Waits 60 Seconds When The Forge Names No Wait",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 44,
  "blockedBy": [],
  "covers": "When a forge names no wait, `Build2.RateLimited` answers `retryAfter: 60`, as `Review1` does, not 0"
}
```

**Why this size.** proposed: one default in the `Build2` interface's `RateLimited` and the adapters that answer it.

**What must be true.** When a forge limits how often it is asked and names no wait, `Build2.RateLimited` answers
`retryAfter: 60`, not 0, as `Review1` does. A caller that retries at once on 0 asks a limited forge again at once, and
is limited again. `Review1` answers 60 seconds, as the forge suite rule does, and `Build2` follows.

## Acceptance

- A rate limit without `Retry-After` is answered `RateLimited` with `retryAfter: 60` by `Build2`, as by `Review1`.
- A named wait is answered as the forge named it.
- The interface's comment in `org.fuin.porelo.Build2.varlink` says 60, not 0.
- Seen to fail first: today `Build2` answers 0 when the forge did not say.
