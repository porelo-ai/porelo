# B282 — A Failed Continuation Is Forgotten

```json
{
  "$schema": "issue-1.json",
  "id": "B282",
  "title": "A Failed Continuation Is Forgotten",
  "state": "Accepted",
  "size": "XS",
  "priority": "Medium",
  "rank": 71,
  "blockedBy": [],
  "covers": "A continuation that fails without naming a session forgets the recorded one, so the next start is fresh and says so"
}
```

**Why this size.** XS: one place in `TaskLaunch`'s run step and its test, no contract. The run already knows the
session it continued and reads the one the run names afterwards; when it continued one, failed and names none,
`TaskSession.forget` drops the record there, and the next start already says "a fresh one". Estimate: under an hour,
below Core's median for S (45 minutes).

**What must be true.** A run asked to continue a recorded session that fails without naming a session of its own has
the recorded session forgotten, so the next start is fresh and says so, as the decision on sessions states; nothing
runs twice without being asked.

## Why

`TaskLaunch.runAgentSteps` records the session a run names, but when a continuation fails and names none, the recorded
session stays: `TaskSession.forget` is called only when a task is removed (`TaskControl`, `Prune`). The next start then
asks to continue the same failed session again.
