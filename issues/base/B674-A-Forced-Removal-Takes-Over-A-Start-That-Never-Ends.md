# B674 — A Removal Retires Its Name Though The Daemon Writes Its Mailbox

```json
{
  "$schema": "issue-1.json",
  "id": "B674",
  "title": "A Removal Retires Its Name Though The Daemon Writes Its Mailbox",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "A task's removal retires its name's claim even when the daemon writes into its mailbox while the mailbox is deleted, so `project remove --force` and `project clear` are no longer refused by an unfinished start of a task already removed."
}
```

**Why this size.** a staged write that cleans up after itself, a watcher that leaves a removed task's mailbox alone, a delete that tolerates a late write, and a retire that a failed delete cannot skip; with tests, no contract.

Filed 2026-11-12 from an agent repository's acceptance run, straight into the backlog as the bug rule says.

**What was seen.** After an unattended task `busy` was started and its scenario passed, `porelo task remove busy
--force` and then `porelo project remove busy --force` answered "task busy has an unfinished start; retry it or
remove that task first" (exit 70). The project could not be removed although its only task had been asked away by
force, and the refusal's way out, removing the task, had already been taken.

**Found, from the run's own failure log (2026-11-12, Agent Smith on the VM).** The reading this issue was filed with
(a start in porelod holding the claim) does not hold: `TaskLaunch` gives the claim back (pid 0) before the agent
runs. The account's `failures.log` holds the removal's own failure:

    porelo task remove
    UncheckedIOException: Removed the task but could not delete its mailbox: porelo-busy_busy
      at TaskControl.stopClaimed ... <- TaskRemoveCommand.removeTask
    Caused by: DirectoryNotEmptyException: <state>/mail/porelo-busy_busy
      at Mailbox.delete <- TaskControl.stopClaimed

- `TaskControl.stopClaimed` deletes the mailbox, the guide and the repositories file, and only then retires the claim
  (`TaskReservations.retire`). A failed delete throws past the retire, so the claim stays CREATED with its container
  gone, which `TaskReservations.projectBusy` reports as an unfinished start; a later start of the name is refused as
  the name of another task.
- What filled the directory: the daemon's message pass (`MessageWatch`, about once a minute) writes each mailbox's
  guide and card (`MailboxGuide.put`) through a temp file `.guide<n>.tmp` in the mailbox's root, then moves it into
  place. One pass ran during the delete: its temp file landed after the delete's walk and before its rmdir. Its move
  failed, and `put` leaves the temp file behind. Every later pass did the same for the removed task, so 284 temp files
  piled up.

**What must be true.** A removal of a task retires its name's claim, whether or not something writes into its
mailbox at the same time. The daemon writes no guide into a removed task's mailbox, and a failed guide write leaves
no temp file behind. `project remove` and `project clear` then find no unfinished start of a task already removed.

## Plan

1. **Red first** (unit level, no VM):
   - a mailbox whose delete meets a file written between its walk and its rmdir: the removal still retires the
     claim, and `projectBusy` answers null;
   - `MailboxGuide.put` whose move fails leaves no `.guide*.tmp`;
   - the message pass over a mailbox whose task is retired or gone writes nothing into it.
2. `MailboxGuide.put`: delete the staged file when the write, the permissions or the move fail.
3. `MessageWatch`: skip a mailbox whose task's claim is retired, or that is being removed. Use the name lock the
   removal already holds (`TaskReservations.mutate`) or the claim's stage, whichever the pass already reads.
4. `Mailbox.delete`: after the walk, retry a directory that is not empty a bounded number of times. A late temp file
   of the guide writer is removed with it.
5. `TaskControl.stopClaimed` (and the unmade path in `remove`): retire the claim even when deleting the mailbox, the
   guide or the repositories fails. Report the failed delete as now, after the retire, and do not throw past it.
   Keep what a later removal or clean-up needs to finish the leftover.
6. `LooseFiles` already names stray `.guide*.tmp` files; check that a leftover mailbox of a retired task is offered
   for clean-up.

## Acceptance

- A task removed while the daemon writes its mailbox leaves its claim retired, and `project remove --force` of its
  project answers 0; a test fails while the delete's failure skips the retire.
- No `.guide*.tmp` is left in a mailbox after a failed guide write.
