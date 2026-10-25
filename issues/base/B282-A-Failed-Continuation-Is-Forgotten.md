# B282 — A Failed Continuation Is Forgotten

**Status:** Idea, filed on the operator's word.

**What must be true.** A run asked to continue a recorded session that fails without naming a session of its own has
the recorded session forgotten, so the next start is fresh and says so, as the decision on sessions states; nothing
runs twice without being asked.

## Why

`TaskLaunch.runUnattended` records the session a run names, but when a continuation fails and names none, the recorded
session stays: `TaskSession.forget` is called only when a task is removed (`TaskControl`, `Prune`). The next start then
asks to continue the same failed session again.
