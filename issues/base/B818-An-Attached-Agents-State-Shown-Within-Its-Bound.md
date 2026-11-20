# B818 — An Attached Agent's State Shown Within Its Bound

```json
{ "$schema": "issue-1.json", "id": "B818", "title": "An Attached Agent's State Shown Within Its Bound",
  "state": "Backlog", "size": "S", "priority": "High", "rank": 141, "blockedBy": [],
  "covers": "The three acceptance scenarios of an attached agent's shown state (working, at rest, waiting) failed together once on the fedora leg within their 60 s and passed on a rerun with no change; the cause is found and fixed, never retried away" }
```

**Why this size.** S: one step class of the acceptance kit and, where the cause lies there, the reading of a task's
screen in `app-task`; no contract.

**What must be true.** The Build's scenarios of an attached agent's shown state pass or fail by what Porelo shows,
never by how fast a leg starts a task.

## Found

On one Build (fedora leg, first attempt), the scenarios "an attached agent at work is said to be working, never at
rest", "an attached agent that answered rests at its prompt and is said to" and "an attached agent that puts a
question to the person is shown waiting" failed together: "Porelo did not settle on showing the stub agent working
within 60s" (and at rest, and waiting). The rerun of the same commit passed both legs; no other run in seven days
failed them.

Read in the code (`acceptance/kit`, `WaitingSteps`): the 60 s (`SHOWS_WITHIN`) run from the task's creation
(`existsBy`), so they hold the container's start, the agent's start and its first screen as well as the shown state
settling, and `showsActivity` also needs `HOLDS_FOR` of an unchanged answer inside them. Inferred, not measured: all
three failing in one run points at what they share, the start's cost on that leg or the screen's first reading, not at
the state each declares.

## Plan

- Measure first, from the Build's suite log of the failed attempt and of passing runs: when each of the three tasks
  was created, when it first answered a status with a screen, and when the expected state was first read.
- Fix the cause found:
  - where the start's cost is what ran out, the bound counts from the agent's first screen, not the task's creation,
    with its own bound for the start, each named in the failure;
  - where the status went on reading no screen or a wrong one, the screen reading is fixed in `app-task` with a unit
    test over a recorded screen, seen red first.
- No retry, and no longer bound without the measurement that shows the real cost.

## Acceptance

- The measurement is written here with its numbers; the fix names which of the two it was.
- The three scenarios pass on both legs of the Build that carries the fix, and the failure, provoked once by a start
  held back locally, names what it waited for.
