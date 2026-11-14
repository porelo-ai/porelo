# B762 — A Failed Start's Caller Key Outlasts A Reboot

```json
{
  "$schema": "issue-1.json",
  "id": "B762",
  "title": "A Failed Start's Caller Key Outlasts A Reboot",
  "state": "Review",
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

## Built

- Read first: a task kept after its hand-over has its record saved by `TaskState.save` before the hand-over in
  `TaskLaunch`, and again by the stop that ends a running task (`TaskControl`). A start that fails before the
  hand-over saves in `cleanUp`'s failed branch, or removes a task that only read. So no path was left open.
- `TaskRunCommandTest.aTaskPastItsHandOverCarriesTheCallersKeyAfterAReboot`, three cases, each started with
  `--caller-key offer-17`, its runtime directory then emptied as a reboot empties it, and its row read: a run that ends
  and leaves its task up (only the save before the hand-over carries the key); a task that reads its project whose run
  fails, stopped and kept; and one whose run ends well, stopped and kept. Each row carries `callerKey` "offer-17".
- Red without the save before the hand-over: the case left up fails, and passes with it. The two kept cases are also
  saved by the stop; with that save taken out as well, they go red too.
- The agy reading (gemini-4-argon-high) found no open path, and one fault in the first test: its non-reading case failed
  before the hand-over (in the workspace's preparation), so it repeated the existing test of a start that failed early.
  It was replaced by the case left up.
- The existing tests of a start that failed before its hand-over, and of a container that never ran, stay green beside
  it (7 in all).
- No production code changes.
