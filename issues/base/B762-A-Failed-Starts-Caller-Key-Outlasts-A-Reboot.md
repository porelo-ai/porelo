# B762 — A Failed Start's Caller Key Outlasts A Reboot

```json
{
  "$schema": "issue-1.json",
  "id": "B762",
  "title": "A Failed Start's Caller Key Outlasts A Reboot",
  "state": "Backlog",
  "priority": "High",
  "size": "S",
  "rank": 8,
  "blockedBy": [],
  "covers": "Every start that keeps its task after a failure - kept by default, one that only read, one handed over - leaves the caller's key where a reboot does not reach, so List gives it back after a machine restart",
  "openQuestions": 0
}
```

**Severity:** high - a caller that restarts after the machine did finds no task by its key and starts a second one,
which Tasks1's row `callerKey` promises cannot happen; nothing leaks.

**Why this size.** S: one module's clean-up path and its tests, no contract change. Estimate: 38 minutes (Core's
median for S).

**What must be true.** A task kept after its start failed carries its caller's key after a machine restart, as
Tasks1 promises for every task whose container was made.

## Plan

- Today the start writes `caller-key` into the runtime state first (`TaskLaunch`, before the starting branch), and
  `TaskInventory.callerKey` reads it from there, else from the record `TaskState.save` writes under
  `taskRecord(container)`. `TaskRunner.recordCreated` puts only the container id into that record.
- The record gets the key on two paths: `TaskState.save` right before the hand-over, and the failed branch of
  `TaskLaunch.cleanUp`, which saves before it stops a task that is not kept (`!kept && (!reading || limited)`).
  The kept branch (`handedOver && request.keep()`) and a task that only read never save there; a reading of the
  finished work left them as a known gap, since the Tasks1 text makes no such exception.
- Read in today's code, a kept failure comes only after the hand-over's save, and a failed reading task before it is
  removed, so the gap may already be closed. The first step decides it: tests in `TaskRunCommandTest` for each path -
  a run handed over that then fails without `--rm`, a reading task that fails after its container was made, a task
  handed over whose save is the last before a reboot - each starting with `--caller-key`, emptying the runtime
  directory, and reading the row. A path that goes red gets one `TaskState.save` in `cleanUp` before the stop,
  whatever `kept` and `reading` say (the save is idempotent and never fails the caller).
- If every path stays green, the tests stay as the guard, and the issue is handed over with that finding.

## Acceptance

- Each path's test, emptied runtime directory included, shows `callerKey` "offer-17"; a path that was red is seen
  red first, then green.
