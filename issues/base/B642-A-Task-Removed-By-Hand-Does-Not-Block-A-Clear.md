# B642 — A Task Removed By Hand Does Not Block A Clear

```json
{
  "$schema": "issue-1.json",
  "id": "B642",
  "title": "A Task Removed By Hand Does Not Block A Clear",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 82,
  "blockedBy": [],
  "covers": "A task removed with porelo task remove leaves no claim that porelo project clear reads as an unfinished start, so the project can be cleared after it."
}
```

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 24's clean-up. The task `porelo-foo-project_all` had been
removed by hand during the run (`porelo task remove`, answered "removed"). Later, `porelo project clear --yes --force
foo-project` was refused: "task all has an unfinished start; retry it or remove that task first".

Read in the code, not yet measured: `TaskReservations.projectBusy` counts a claim as an unfinished start when it is
live, still `RESERVED`, or its container is gone. So a claim that outlives its removed container blocks the clear, and
the refusal sends the person to remove a task that is already gone.

**What must be true.** After `porelo task remove` answers "removed", no claim of that task is left for a clear to
refuse on. A claim whose container is gone, and which no start holds, does not block a clear. A start that is really
unfinished still does.
