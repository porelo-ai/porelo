# [B522](README.md#b522) — An Online Task On A Host Pushes Its Amend

**Status:** Review. Filed 2026-11-08 for move 4 (an `online` project on a host), from Core's reading of 19:45Z.
**Size:** S - `HostPush` pushes the task's own branch with a lease.

**What must be true.** An online task on a host that amends its commit after a reading, as each task did in move 3's
second round, has the amended commit reach `porelo/<task>` on the host's forge. The push replaces only what the gate
itself last pushed there (`--force-with-lease=refs/heads/porelo/<task>:<that sha>`), never another branch, and never
work it did not push.

## Why

`HostPush` pushes `porelo/<task>` without force (HostPush.java:17-18), so a rewritten branch is refused as
non-fast-forward, and move 4's "work every result off until done" stops at the first amend.

## As built

- `HostPush.passOn` keeps the commit the forge took under `<mirror>/porelo-host-pushed/<task>`, a file git never
  serves, and leases the next push on it: `--force-with-lease=refs/heads/porelo/<task>:<that commit>`. Before the
  first push, or with no record, it pushes with `--no-force-with-lease`, so it replaces nothing, as before. A push the
  forge refused leaves the record as it was.
- Test: `HostPushTest.anAmendReplacesOnlyWhatTheGateItselfLastPushedThere`, seen red against main.
- For Agent Machines: the host's forge must let the task's token force-push its own `porelo/<task>`, under whatever
  branch protection it sets there. A refusal is said in the gate's words, as every other one is.
- After the reading:
  - `GitGate.forget` also removes `<mirror>/porelo-host-pushed/<task>` (the name is now `GitGate.HOST_PUSHED`), so
    a new task named like a removed one no longer leases on the removed task's commit. Test:
    `GitGateTest.forgettingATaskAlsoForgetsWhatItsHostPushLastPushed`, seen red before.
  - `passOn` reads the lease, sets the mirror's branch, pushes and writes the record under one per-task file lock,
    `<mirror>/porelo-host-pushed/.<task>.lock`, and pushes `<commit>:refs/heads/porelo/<task>` rather than the
    mirror's branch, so two pushes of one task at once can no longer send one commit and record the other. Test:
    `HostPushTest.thePushSendsTheCommitItWasGivenNotWhatTheMirrorsBranchHolds`, seen red before.
  - Tests that a record that is no commit leases nothing (`HostPushTest.aRecordThatIsNoCommitLeasesNothing`), and
    that the first push says `--no-force-with-lease`.

