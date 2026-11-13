# B610 — A Removed Task's Kept Mail Record Frees Its Name

```json
{
  "$schema": "issue-1.json",
  "id": "B610",
  "title": "A Removed Task's Kept Mail Record Frees Its Name",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "`task remove` takes the task's mailbox with it, its signed recorded mail moved to an archive first, so the task can be started again under its name."
}
```

**Why this size.** a record writer and the conversation hand-out made to leave a deleted mailbox alone, and removal archiving a mailbox's record before it removes the mailbox; the reservation check stays; tests in three places.

Filed 2026-11-11 from [B602](README.md#b602)'s real-machine run.

**What must be true.** On the upgrade VM, `porelo task remove porelo-porelo-kept --force` on a task with signed,
recorded mail removed the container but left the `record` of its mailbox (`mail/porelo-porelo-kept` in the state directory) and a `RETIRED`
identity record. The next start of the same task was refused with `ContainerNameTaken`: `TaskReservations` keeps a
retired name taken while its mailbox exists. Nothing said what holds the name or how to free it.

**Decided.** `task remove` takes the task's mailbox with it, so the name is free again. Signed recorded mail is moved
to an archive first, outside the state the reservation reads, so the evidence stays and nothing holds the name.

## Plan

Rewritten 2026-11-13: the first Plan does not fit main. **Found.** On main `task remove` already deletes the whole
mailbox, `record` included, before it retires the name (`TaskControl.stopRecorded`). What the VM found was made
after the removal: the daemon's conversation loops (`MessageWatch`'s waiting poll) work on the members the last pass
found, up to a minute old, and their read pass (`TransportConversations.confirm`/`markRead`/`askRead`) and hand-out
(`handOut`) write into those members' records. `MessageRecord.append` (through `FileLocks.holding`) and `handOut`
create every missing parent, so a deleted mailbox came back as `mail/<container>/record`, and
`TaskReservations` rightly keeps a retired name taken while it is there. Archiving before the delete alone would not
free the name: the late write comes after it.

1. **Red first** (unit level, no VM):
   - `MessageRecordTest`: an append to a mailbox that was deleted makes no mailbox again.
   - `TransportConversationsTest`: a hand-out to a member whose mailbox was deleted makes no mailbox again and hands
     the message to nobody.
   - `TaskControlRemoveTest` (or the removal test that exists): a removed task's record is in the archive, its
     mailbox is gone, and the name is free for `TaskReservations.reserve`.
2. `MessageRecord.append` writes only into a mailbox that exists: it makes `record` (not its parents) and fails with
   `NoSuchFileException` when the mailbox's root is gone. Nothing else creates a mailbox but `Mailbox.create`.
3. `TransportConversations`: `handOut` skips a reader whose mailbox is gone; `markRead` and `askRead` do nothing for
   one; nothing there makes a mailbox's root.
4. Removal (`TaskControl`, removed path): before the mailbox is deleted, its `record` is copied into
   `<state>/mail-archive/<container>-<UTC stamp>/` (owner-only), outside what `TaskReservations` reads; then the
   mailbox goes as before. The reservation check stays.

## Built (2026-11-13)

- `Mailbox.ensure(path)` (app-base) makes folders inside an existing mailbox one level at a time, never the mailbox
  itself, and answers `NoSuchFileException` once it is gone. `MessageRecord.append`, `TransportConversations.handOut`,
  `AgentWake` and `BounceDelivery` use it instead of making every missing parent.
- `handOut` drops a member whose mailbox is gone; `markRead` and `askRead` return at once for one.
- `TaskControl.archiveRecord`, before the mailbox is deleted on both removal paths: the record's files, without lock
  files, go to `<state>/mail-archive/<container>-<UTC stamp>/` (0700); a copy that fails keeps the mailbox.
- Red first: `MessageRecordTest`, `TransportConversationsTest` and `TaskReservationsTest` each failed before. Three
  `MachineTransportsTest` fixtures now make the task's mailbox, as a start does; they had relied on `handOut` making it.
- doc/messages.md says where the kept record goes.
- After the reading: every lock inside a mailbox (`.record.lock`, `.wake.lock`, `.pass.lock`) is taken with
  `FileLocks.holdingInside`, which makes the lock file but never its folder, so a removal between the check and the
  lock is not undone. `PersonNote`, `MessageSay`, `MessageDispatch`, `MessageRelease` and `TransportSend` make their
  folders through `Mailbox.ensure`, which also refuses a link at any path component; `Mailbox.gone()` tells their
  callers the task was removed.
- The record is copied first on both removal paths and under its own lock; a failed copy removes its half and says the
  removal can be run again. `mail-archive/` and everything in it is the account's alone (0700, files 0600) from its
  making. A late wake for a task with no mailbox, record or container state makes no record of it.
- Red first for each: `MessageRecordTest`, `AgentWakeTest`, `MessagePassTest`, `MessageReleaseTest`, `MessageSayTest`,
  `PersonNoteTest`, `TransportSendTest`, `TaskReservationsTest`, `TransportConversationsTest` and `MailboxTest`; a
  test helper lands the removal while the writer waits at its lock. Not tested on their own: the small "no mailbox"
  answers of `TaskRefresh`, `talk tell`, `talk say` and the rejected-work note. Left open: a line appended after the
  copy and before the delete is not in the archive.

## Acceptance

- A task removed with signed, recorded mail leaves no `mail/<container>`, its record is in the mail archive, and the
  next start of the same name is not refused with `ContainerNameTaken`.
- A late write of the daemon into a removed task's record or inbound does not make its mailbox again.

**Size.** M: two writers made to leave a deleted mailbox alone, an archive step in the removal, tests in three places.
