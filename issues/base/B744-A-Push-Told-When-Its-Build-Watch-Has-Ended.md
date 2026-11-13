# B744 — A Push Told When Its Build Watch Has Ended

```json
{
  "$schema": "issue-1.json",
  "id": "B744",
  "title": "A Push Told When Its Build Watch Has Ended",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "A push made after a task's build watch has ended is answered, as one made when it never started is, that no build of it will be reported.",
  "openQuestions": 0
}
```

**Why this size.** S: the gate asks at each push whether the watch still runs, through the helper's recorded pid, and
answers as for `--builds-unwatched`; one test each for a watch that ended and one that runs. Estimate: 45 minutes
(Core's median for S).

**What must be true.** A task's agent is never left waiting for a build report that no watch will make.

Found while building [B712](README.md#b712), which answers a push when the watch never started. A watch that starts
and then ends, for example when its reader fails at run time, is not seen by the gate: a push after it still waits for
a build that is never reported.

## Plan

- The gate, at each passed-on push of a project that follows builds, checks the watch through its pid file and
  `HelperPid`, as the start checks its helpers. A watch that is not running gets the verdict `none` with the reason
  `UNWATCHED`, as [B712](README.md#b712) gives.
- First red, in `apps/app`: a gate whose watch's pid names no running process answers `none` and `UNWATCHED` at a
  push; with a running watch it still answers as before.

## Acceptance

- A push after the task's build watch has ended is told at once that no build of it will be reported.
- A push while the watch runs is unchanged.
