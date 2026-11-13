# B712 — An Unknown Build Where A Watch Never Starts

```json
{
  "$schema": "issue-1.json",
  "id": "B712",
  "title": "An Unknown Build Where A Watch Never Starts",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 66,
  "blockedBy": [],
  "covers": "A task whose project follows builds, but whose build watch did not start, tells its agent at each push that no build will be reported, as a project that follows no builds does.",
  "openQuestions": 0
}
```

**Why this size.** One condition at the start and its test.

Filed 2026-11-13 from building the hand-in at the push, which tells an agent at once when no build of its push will
come: a push held at the gate for a person, or a push in a project that follows no builds. The gate is told whether
builds are followed from the project alone. When the project names builds but the watch then fails to start (no
credential for the forge, the build reader not installed, the helper failing), the gate still thinks a build will be
reported. No `unknown` is handed in, so the agent waits for a report that never comes, the same fault in a rarer
case.

**What must be true.** The gate is told that builds are followed only when the watch actually runs.

## Plan

- Decide the gate's builds flag after the build watch has started (or failed), not from the project alone. On a
  resume, the same.
- Red first: a start whose build watch fails starts its gate as one that follows no builds, and a push hands in
  `unknown` with its reason.

## Acceptance

- The test holds and fails without the change.

## Built

- **The watch starts before the gate.** In `TaskLaunch`, the build watch now starts right after the relays, so the
  gate knows whether it runs. `BuildsWiring.start` tells whether the helper started; it did not when a host credential
  or a reader is missing, there is no upstream or branch, or the process failed to start. The watch's handle goes to
  the start's own helpers, so a start failing at the gate ends it. The "builds" and "pull request" lines now come
  before the gate's.
- **The gate's `--builds-unwatched`.** `GateWiring.serving` passes it for an online task whose project follows builds
  and whose watch did not start. At each push such a gate answers the verdict `none` with the new reason `UNWATCHED`:
  "the watch that reports this task's builds did not start; no build of this push is reported". That is the verdict a
  project following no builds gets, since no build of that push is ever reported later. `--no-builds`, which says the
  project follows none, is unchanged.
- **Resume.** `TaskControl.buildsWatched` sets or drops the flag on the recorded gate command, depending on whether
  this resume runs the builds helper. A reader deferred at the start that joins on resume drops it, and a task
  started before this change gains it. The command is worked out again on each resume and never written back.
- **Tests.**
  - New: two in `GateServingTest` (the start's flags, and the resume rewrite), one in `GateUnbuiltPushTest`
    (`none` and `UNWATCHED` at once), and one in `BuildsWiringTest` (it follows builds, but with no reader the watch
    does not start).
  - On the build box they failed before the change (`buildbox-porelo-3feda3c92103-20261113T173322Z.log`, 5 failures)
    and pass after it (`buildbox-porelo-cff13504a2f3-20261113T173538Z.log`), with 359 tests across
    TaskRunCommandTest, TaskStateTest, the gate and builds tests, 0 skipped.
- **Not covered:** a watch that starts and later ends (its reader failing at run time) is not caught at a push. That
  is [B744](README.md#b744).
