# [B522](README.md#b522) — An Online Task On A Host Pushes Its Amend

**Status:** Backlog. Filed 2026-11-08 for move 4 (an `online` project on a host), from Core's reading of 19:45Z.
**Size:** S - `HostPush` pushes the task's own branch with a lease.

**What must be true.** An online task on a host that amends its commit after a reading, as each task did in move 3's
second round, has the amended commit reach `porelo/<task>` on the host's forge. The push replaces only what the gate
itself last pushed there (`--force-with-lease=refs/heads/porelo/<task>:<that sha>`), never another branch, and never
work it did not push.

## Why

`HostPush` pushes `porelo/<task>` without force (HostPush.java:17-18), so a rewritten branch is refused as
non-fast-forward, and move 4's "work every result off until done" stops at the first amend.
