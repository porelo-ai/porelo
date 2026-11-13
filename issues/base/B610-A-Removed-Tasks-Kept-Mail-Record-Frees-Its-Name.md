# B610 — A Removed Task's Kept Mail Record Frees Its Name

```json
{
  "$schema": "issue-1.json",
  "id": "B610",
  "title": "A Removed Task's Kept Mail Record Frees Its Name",
  "state": "Accepted",
  "size": "S",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "`task remove` takes the task's mailbox with it, its signed recorded mail moved to an archive first, so the task can be started again under its name."
}
```

**Why this size.** removal moves a mailbox's record to an archive and then removes the mailbox; the reservation check stays; tests.

Filed 2026-11-11 from [B602](README.md#b602)'s real-machine run.

**What must be true.** On the upgrade VM, `porelo task remove porelo-porelo-kept --force` on a task with signed,
recorded mail removed the container but left the `record` of its mailbox (`mail/porelo-porelo-kept` in the state directory) and a `RETIRED`
identity record. The next start of the same task was refused with `ContainerNameTaken`: `TaskReservations` keeps a
retired name taken while its mailbox exists. Nothing said what holds the name or how to free it.

**Decided.** `task remove` takes the task's mailbox with it, so the name is free again. Signed recorded mail is moved
to an archive first, outside the state the reservation reads, so the evidence stays and nothing holds the name.

## Plan

- Removal moves the mailbox's signed recorded mail to an archive outside what `TaskReservations` reads, then
  removes the mailbox.
- Red first: a `TaskReservationsTest` case for a retired name whose only remains are a mailbox record.
