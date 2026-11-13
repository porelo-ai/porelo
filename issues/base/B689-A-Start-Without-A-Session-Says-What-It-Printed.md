# B689 — A Start Without A Session Says What It Printed

```json
{
  "$schema": "issue-1.json",
  "id": "B689",
  "title": "A Start Without A Session Says What It Printed",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 43,
  "blockedBy": [],
  "covers": "The acceptance kit's wait for a task's session, when none appears, ends with what the terminal showed, through the terminal's redaction, so a `task start` that exits at once says why.",
  "openQuestions": 0
}
```

**Why this size.** one failure message in the kit's `ReadySteps` and one unit test with a stand-in terminal.

Filed 2026-11-13 for Agent Smith, from two scenarios of an agent repository's acceptance that each waited 900 seconds
with nothing to read: their `task start` had been refused at once, and the failure did not say so.

**What must be true.** When the kit waits for a task's session and none appears, its failure ("No session to read
appeared in <container> within 900s") ends with what the scenario's terminal showed, passed through the terminal's
own redaction, so a refusal printed by `task start` is in the failure.

## Plan

- `ReadySteps`: append `world.terminal().seen()`, redacted as the terminal redacts what it shows, to that failure.
- Red first: a unit test whose stand-in terminal holds a refusal line and never a session sees the line in the
  failure.

## Acceptance

- A start refused at once fails its scenario naming the refusal; a test fails while the failure leaves it out.
