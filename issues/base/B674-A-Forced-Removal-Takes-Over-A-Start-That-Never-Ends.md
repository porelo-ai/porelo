# B674 — A Forced Removal Takes Over A Start That Never Ends

```json
{
  "$schema": "issue-1.json",
  "id": "B674",
  "title": "A Forced Removal Takes Over A Start That Never Ends",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "`task remove --force` ends the name's claim even while a start in another process still holds it, so `project remove --force` and `project clear` are no longer refused by an unfinished start of a task already removed."
}
```

**Why this size.** one rule in `TaskReservations` and its call in `TaskControl.remove`, with tests; no contract.

Filed 2026-11-12 from an agent repository's acceptance run, straight into the backlog as the bug rule says.

**What was seen.** After an unattended task `busy` was started and its scenario passed, `porelo task remove busy
--force` and then `porelo project remove busy --force` answered "task busy has an unfinished start; retry it or
remove that task first" (exit 70). The project could not be removed although its only task had been asked away by
force, and the refusal's way out, removing the task, had already been taken.

**Found, read in the code.**
- A start claims its name in `TaskReservations` with the pid and start instant of the process that runs it
  (`ProcessHandle.current()`), and drops the pid only when the start finishes (`held(0, "")`). A start run by porelod
  is held by porelod's pid; an unattended run's Start finishes only when its run ends, so while the agent runs, the
  claim reads `live()` for as long as porelod runs.
- `TaskReservations.retire` throws `taken` when the claim is `live()` and its attempt is not this process's. That is
  the last step of `TaskControl.remove` (`removeExistingTask`), after the container, its state and its secrets are
  gone. So a forced removal from the command line deletes everything and leaves the claim as it was.
- `TaskReservations.projectBusy` then finds that claim live, or its container absent, and refuses the project.

**What must be true.** A forced removal of a task ends its claim whoever held it, once the container is gone; the
start that held it, if it still runs, fails on its next step as for any removed container, and its own last write
cannot bring the claim back. A removal without `--force` keeps refusing a live start, naming it.

## Plan

- `TaskReservations`: `retire(container, force)`; with force, write RETIRED over a live claim of another attempt, and
  have the holder's `publish`/release refuse to write over a RETIRED claim (it re-reads under the lock already).
- `TaskControl.remove(..., force)`: pass force to `retire`, on both the made and the unmade path.
- Red first: a claim held by another live process (a `sleep` the test starts, with its pid and start instant), a
  forced removal, then `projectBusy` answers null and the claim reads RETIRED; without force the removal is refused
  as today.

## Acceptance

- A project whose task was removed by force while its start still held the name is removed by `project remove
  --force`; a test fails while `retire` refuses the forced case.

## To be checked

- That the run's own log shows the removal ended at `retire` (`Cannot retire task identity` or the `taken` refusal),
  as read; the agent repository's run log holds it.
