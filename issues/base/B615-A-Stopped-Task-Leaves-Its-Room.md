# B615 — A Stopped Task Leaves Its Room

```json
{
  "$schema": "issue-1.json",
  "id": "B615",
  "title": "A Stopped Task Leaves Its Room",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "`porelo task stop` on a project whose conversation is on a central homeserver runs the transport's `retire` for the task, so its account leaves the room; the next start's `enroll` brings it back, so the room lists the tasks that run."
}
```

**Why this size.** `retire` run at a stop besides a removal, for a central homeserver alone, the contract's row, unit tests
over a stand-in transport.

Filed 2026-11-11 from the VM proof's busy-room run on 0.5.1~snapshot.484.
Accepted by the operator 2026-11-11.

**What must be true.** A task stopped with `porelo task stop` is no longer a member of the project's room, and the next
start of the task brings it back. Today the transport contract has `enroll` at a start and `retire` at a removal, and
nothing at a stop. So a stopped task's account stays in the room, and a rehearsal that counts the running tasks among
the room's members counts it too.

On a central homeserver the matrix transport's `retire` does what a stop needs: the account leaves the room, its
sessions are logged out, its name stays free, and the next `enroll` invites it again. On a person's own homeserver
`retire` deactivates the account for good, so a stop there runs nothing until the transport offers a verb for it.

[B614](README.md#b614) did not change this: before it, a new task's account was not read as a task at all, and a task
made before stayed in the room when stopped, as now.

## Plan

- `TaskConversations` gets a stop: for each of the project's transports whose kept task secrets hold a non-empty
  `PORELO_MATRIX_LOGIN`, it runs `retire` with the name the task was enrolled under, as at a removal, and forgets the
  task's secrets and address. That secret, printed by `setup` and `enroll`, is non-empty on a central homeserver and
  empty on the account's own (the matrix transport's `transport.md`, "The printed secrets"). Taking `--login` says
  nothing of it: the matrix transport takes it for every project. A transport without that secret is not asked.
- `task stop` (and the daemon's `Stop`) calls it after the container stopped. A failed `retire` still leaves the task
  stopped, and says so on standard error with the transport's cleaned words.
- The start that brings the task back already runs `enroll` before its container runs again.
- Red first: a `TransportConversationsTest` case stops a task on a stand-in transport whose task secrets hold a
  non-empty `PORELO_MATRIX_LOGIN` and expects `retire` with the enrolled name, then a start that runs `enroll` again;
  with it empty, or absent, nothing runs at the stop.
- `doc/transports.md`, the `retire` row: when a task is removed, and when it is stopped on a central homeserver. Changelog
  under *Fixed*.

## To be checked

- The signal is one transport's secret, read by its name; the contract names no such signal for any transport.
  Whether the contract gets a general one (an answer of `enroll` saying a `retire` can be undone by the next
  `enroll`) is for the contract's next change; this issue reads the matrix name alone.
- A task that ends without a stop (its container exits, the machine restarts) runs nothing. Whether the daemon's watch
  of a task's end runs it too is the operator's decision; until then a rehearsal that counts running tasks takes them
  from Porelo, not from the room alone.

## As built

- `TransportLifecycle.leave` reads `PORELO_MATRIX_LOGIN` from the task's kept secrets, else the project's, and runs
  `retire` only when it is non-empty; `TaskConversations.leave` runs it for each of the project's transports.
- `TaskControl` runs it after a confirmed stop that removes nothing, so `task stop`, the daemon's `Stop`, and the stops
  before a removal or a clear all take it. A bulk stop (`panic`) asks no transport. A locked vault reads no secret,
  so such a stop leaves the task in the room.
- Built as a fix during an operator test: its unit tests seen red, then green; the reading by another model follows.
