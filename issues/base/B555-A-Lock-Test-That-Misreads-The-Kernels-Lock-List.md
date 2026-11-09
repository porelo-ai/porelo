# [B555](README.md#b555) — A Lock Test That Misreads The Kernel's Lock List

**Status:** Review. Filed 2026-11-09 in Backlog, priority Highest (the first row), as a flaky test that blocked a
delivery, under the shared rule; fixed the same day, at the first try.
**Size:** S - the test's own reading of the kernel's lock list, no product code.

**What must be true.** `FileLocksTest.aProbeNeverDropsTheLockAnotherThreadOfThisProcessHolds` fails only when a probe
really drops the lock another thread of the process holds, never because its own read of the kernel's lock list missed
a line.

## Why

On 2026-11-09 the test failed once in the host reactor of a whole verify (`holds whose lock the kernel no longer had`,
one of many holds in its five seconds), and stopped that delivery's chain. It then passed 6 of 6 runs alone, and the
code it covers (`FileLocks.free` and `holdingIfFree`, from [B530](README.md#b530)) was not changed by that delivery.
`FileLocks.free` probes only while the process's own lock for the path is free, so a probe cannot close the holder's
channel while it holds.

The suspected cause is the test's check, `heldHere`: it reads `/proc/locks` once per hold. The kernel produces that file
in pieces while every process on the machine takes and drops locks, and a read during a busy reactor can miss a line,
so a lock that is held reads as gone.

## Plan

- `heldHere` reads `/proc/locks` until two reads in a row agree on whether this process holds a lock on the file's
  inode (a few reads at most), and counts a drop only then.
- Seen red first by a seam that makes the first read miss the line: before the change the hold counts as dropped,
  after it it does not. The real check stays: with the probe's guard taken out of `FileLocks.free`, the test still
  fails.

## As built

- `heldHere` reads the list through `heldIn`, which reads until two reads in a row agree (at most five), and with no
  agreement answers whether any read listed the lock: a miss in one read no longer counts as a drop.
- Seen red first: `aReadOfTheKernelsListThatMissesTheLineOnceStillReadsTheLockAsHeld` failed with `heldIn` cut back to
  one read, and passes with it.
- The real check stays: with the guard in `FileLocks.free` taken out, the probe test failed (`holds whose lock the
  kernel no longer had`), and passes with it in. `FileLocksTest` passed five runs in a row.
