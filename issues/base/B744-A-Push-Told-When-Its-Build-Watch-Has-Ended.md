# B744 — A Push Told When Its Build Watch Has Ended

```json
{
  "$schema": "issue-1.json",
  "id": "B744",
  "title": "A Push Told When Its Build Watch Has Ended",
  "state": "Review",
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

## Built

- `UnbuiltPush.unlessWatched`: at each passed-on push of a project that follows builds and whose watch started, the
  gate reads the watch's pid file (`builds.pid` in the task's state, the file `BuildsWiring` gives the watch) and
  checks it with `HelperPid.verified`. When no running process matches, the push gets the verdict `none` with the
  reason `UNWATCHED` at once. While the watch runs, nothing changes. `GateServeCommand.watchPid` finds the file at
  each push through the task's recorded container.
- `UNWATCHED` now reads "the watch that reports this task's builds is not running; no build of this push is
  reported", since it now also covers a watch that started and ended.
- Tests in `GateUnbuiltPushTest`:
  - `aPushPassedOnAfterItsBuildWatchEndedIsAnsweredAtOnceWithWhy` (a recorded pid whose process has ended)
  - `aPushPassedOnWhileItsBuildWatchRunsIsLeftToTheWatch`

  The first failed on the build box before the change (`buildbox-porelo-62e4e8b32232-20261113T175007Z.log`). Both
  pass after it (`buildbox-porelo-7d46a881c362-20261113T175411Z.log`): 61 tests across the gate and builds tests,
  0 skipped.
- Left as is: a push between the watch's start and its first write of the pid file would be told `UNWATCHED`. The
  watch writes the file first thing, long before an agent's first push.
