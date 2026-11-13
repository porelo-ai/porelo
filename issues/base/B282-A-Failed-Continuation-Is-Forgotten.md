# B282 — A Failed Continuation Is Forgotten

```json
{
  "$schema": "issue-1.json",
  "id": "B282",
  "title": "A Failed Continuation Is Forgotten",
  "state": "Accepted",
  "size": "-",
  "priority": "Low",
  "rank": 71,
  "blockedBy": [],
  "covers": "A continuation that fails without naming a session forgets the recorded one, so the next start is fresh and says so"
}
```

**What must be true.** A run asked to continue a recorded session that fails without naming a session of its own has
the recorded session forgotten, so the next start is fresh and says so, as the decision on sessions states; nothing
runs twice without being asked.

## Why

`TaskLaunch.runAgentSteps` records the session a run names, but when a continuation fails and names none, the recorded
session stays: `TaskSession.forget` is called only when a task is removed (`TaskControl`, `Prune`). The next start then
asks to continue the same failed session again.
