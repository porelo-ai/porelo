# B703 — A Removed Project Leaves No Retired Claim

```json
{
  "$schema": "issue-1.json",
  "id": "B703",
  "title": "A Removed Project Leaves No Retired Claim",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 57,
  "blockedBy": [],
  "covers": "`project remove` takes away the retired task claims of the project's tasks once their leftovers are gone, and the docs say how long a retired claim lives.",
  "openQuestions": 0
}
```

**Why this size.** One step in `project remove` through `TaskReservations`, its test, and a paragraph in the docs.

Filed 2026-11-13 from Agent Smith's acceptance run on the test VM: after the kit's cleanup, `task remove busy --force`
and then `project remove busy --force`, the task's claim in the state directory's `task-identities` stays, stage
`RETIRED`, pid 0.

A retired claim is kept on purpose: `TaskReservations.retired` lets a later prune take a removed task's leftovers by
it, and a new start of the name may take it over. Nothing takes it away when its project goes, so it outlives
everything it could still name.

**What must be true.** After `project remove`, no claim of that project's tasks is left once their state, record,
mailbox and vault leftovers are gone; a claim whose leftovers remain stays, for prune. The docs say this.

## Plan

- `ProjectRemoveCommand`: after its tasks are removed, forget each retired claim of the project whose
  `TaskReservations.retired` holds, through `TaskReservations`.
- Docs: the claim's life in `doc/` where task removal is described.
- Red first: a project with a removed task leaves no claim after `project remove`; a claim with a vault leftover
  still stays.

## Acceptance

- Both cases hold; a test fails while the claim stays.
