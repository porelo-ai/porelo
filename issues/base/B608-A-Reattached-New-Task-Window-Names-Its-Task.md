# B608 — A Reattached New Task's Window Names Its Task

```json
{
  "$schema": "issue-1.json",
  "id": "B608",
  "title": "A Reattached New Task's Window Names Its Task",
  "state": "Accepted",
  "size": "XS",
  "priority": "Low",
  "rank": 30,
  "blockedBy": [],
  "covers": "`task start` going back into an existing task made under the new container names labels its window `<project>/<task>`, not `<project>/`."
}
```

**Why this size.** one label taken from the task's identity instead of its container name; one test.

Filed 2026-11-11 from Core's fix of main's red legs after new tasks got the short container name (the restarted tasks, on the
coordinator's order of 00:58Z to file it with the next issues commit).

**What must be true.** When `task start` goes back into an existing task, the window label it gives the attach names
the task as its record does. Today `TaskRunCommand` (about line 872) cuts `porelo-<project>-` off the container name
with `ContainerName.taskIn`, which finds nothing in a new-form name such as `porelo-recall_attended`, so the label
reads `recall/`. A legacy task keeps its label.

## Plan

- Take the task from the identity the start already resolved (as `--restarted` now takes the inventory's
  `shortName()`), falling back to the container name only when no record names one.
- Red first: a `TaskRunCommandTest` case going back into `porelo-uc_shell` expects the label `uc/shell`.
