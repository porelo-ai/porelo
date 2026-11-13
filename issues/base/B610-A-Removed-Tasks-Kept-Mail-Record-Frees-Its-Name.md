# B610 — A Removed Task's Kept Mail Record Frees Its Name

```json
{
  "$schema": "issue-1.json",
  "id": "B610",
  "title": "A Removed Task's Kept Mail Record Frees Its Name",
  "state": "Implementing",
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

## Acceptance

- A task removed with signed, recorded mail leaves no `mail/<container>`, its record is in the mail archive, and the
  next start of the same name is not refused with `ContainerNameTaken`.
- A late write of the daemon into a removed task's record or inbound does not make its mailbox again.

**Size.** M: two writers made to leave a deleted mailbox alone, an archive step in the removal, tests in three places.
