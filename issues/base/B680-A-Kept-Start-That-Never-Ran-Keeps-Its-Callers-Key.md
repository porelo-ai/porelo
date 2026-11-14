# B680 — A Kept Start That Never Ran Keeps Its Caller's Key

```json
{
  "$schema": "issue-1.json",
  "id": "B680",
  "title": "A Kept Start That Never Ran Keeps Its Caller's Key",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 55,
  "blockedBy": [],
  "covers": "A start that fails under `--keep`, one that only reads, or one handed over, whose container podman made but never ran, saves its caller's key to the task's record, so a reboot before it runs does not lose the key its caller finds it by."
}
```

**Why this size.** the save a start carrying the caller's own key makes on the failing path, on three more paths, and their tests.

Filed 2026-11-12 from the third reading of a start carrying the caller's own key, straight into the backlog as the bug rule says.

**What was seen.** Read in the code, not run. `TaskLaunch` saves the task's state before it stops a failed start's
container, so a reboot keeps the caller's key. A failed start under `--keep`, a reading task and a handed-over task
take other branches and save nothing there; a stop saves only a container that ran. Such a container that never ran
keeps the key only in the runtime directory, which a reboot empties.

**What must be true.** Every task kept after a start keeps its caller's key across a reboot, whether its container
ran or not.

## Plan

- Save the task's state on the `--keep`, reading and handed-over branches of a failed start, as the failing branch
  does.
- Red first: each of the three, made but never run, then the runtime directory emptied: `task list` shows the key.

## Acceptance

- After a reboot, a kept task that never ran is found by its caller's key; a test fails while it is not.
