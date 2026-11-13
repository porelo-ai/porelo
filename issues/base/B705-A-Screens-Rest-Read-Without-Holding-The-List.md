# B705 — A Screen's Rest Read Without Holding The List

```json
{
  "$schema": "issue-1.json",
  "id": "B705",
  "title": "A Screen's Rest Read Without Holding The List",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 59,
  "blockedBy": ["B118"],
  "covers": "Reading whether an attached agent is at rest costs a listing no second-look pause per task, its per-task state is pruned with the task, and the stub's wake and the guards each have a test.",
  "openQuestions": 0
}
```

**Why this size.** A change in where the second look is taken, a prune, a stub scenario check and a handful of tests.

Filed 2026-11-13 from a reading of [B118](README.md#b118) and its follow-up:

- **Listing latency.** A healthy listing pays the second-look pause in each of the 8 pool workers, about 6 s of pauses
  for 30 resting tasks; `task status` derives the whole inventory too, and a second reader of one task waits up to
  about 7.5 s behind its lock. The listing performance test declares no `at_rest`.
- **State never pruned.** `AgentWaiting.reading`, the lock per task, keeps one entry per container name for the
  daemon's lifetime; each fallback capture starts its own platform thread.
- **A timed-out capture** resets the task's rest run, so the next look starts over at `WORKING`.
- **The stub's wake.** `talk-commands.feature:64` and `task-hand-in.feature:30` now reach wake typing; the stub echoes
  its first input without showing work, so `AgentWake` can retry Enter and mark a line stuck, and nothing checks it.
- **Test gaps.** Cached reads aging past 1.5 s, the exact `LOOK` boundary, an interrupted pause, and `Rest.shows`'s
  question veto on its own (`AgentWakeTest` violates `at_rest.lacks` too).

**What must be true.** A listing takes no second-look pause unless asked, the per-task state goes with its task,
and each guard has a test that fails without it.

## Plan

- The second look only where an answer is asked for (`task status` of one task, the daemon's watch), never inside a
  whole listing, which shows the first look's turn as such.
- Prune `reading` when a task is removed; one bounded executor for fallback captures.
- A timed-out capture keeps the run it interrupted.
- The stub shows work after its first input, and the two scenarios check that no line is marked stuck.
- The four missing tests, and the listing performance test with `at_rest` declared.

## Acceptance

- 30 resting tasks list within the performance test's bound; each guard's test fails while it is removed.
