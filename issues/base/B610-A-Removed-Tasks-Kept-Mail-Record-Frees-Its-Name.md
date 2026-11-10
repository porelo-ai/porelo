# B610 — A Removed Task's Kept Mail Record Frees Its Name

```json
{
  "$schema": "issue-1.json",
  "id": "B610",
  "title": "A Removed Task's Kept Mail Record Frees Its Name",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "After `task remove`, a task whose mail was recorded can be started again under its name, or the refusal says what holds the name and how to free it."
}
```

**Why this size.** a decision on what removal keeps of a mailbox, and the reservation check that follows it; tests.

Filed 2026-11-11 from [B602](README.md#b602)'s real-machine run.

**What must be true.** On the upgrade VM, `porelo task remove porelo-porelo-kept --force` on a task with signed,
recorded mail removed the container but left the `record` of its mailbox (`mail/porelo-porelo-kept` in the state directory) and a `RETIRED`
identity record. The next start of the same task was refused with `ContainerNameTaken`: `TaskReservations` keeps a
retired name taken while its mailbox exists. Nothing said what holds the name or how to free it. Either removal takes
the mailbox with it (the record kept elsewhere if it is evidence worth keeping), or the refusal names the kept record
and the command that clears it.

## Plan

- Decide with the operator whether a removed task's mail record is kept, and where.
- Red first: a `TaskReservationsTest` case for a retired name whose only remains are a mailbox record.
