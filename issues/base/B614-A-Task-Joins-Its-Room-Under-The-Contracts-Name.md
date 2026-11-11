# B614 — A Task Joins Its Room Under The Contract's Name

```json
{
  "$schema": "issue-1.json",
  "id": "B614",
  "title": "A Task Joins Its Room Under The Contract's Name",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "A task made under the new container name is enrolled and retired on its transport as `porelo-<project>-<task>`, the transport contract's task name, so the room's member list counts it as the project's task again."
}
```

**Why this size.** one name computed in `TaskConversations` for `enroll` and `retire`, and its way back where a
transport names a task; unit tests over a stand-in transport.

Filed 2026-11-11 from a rental run's finding.

**What must be true.** A task's room account is `@porelo-<project>-<task>.<login>`, as the transport contract's
`members` reads a task. Since a new task's container is named `porelo-<project>_<task>`, Porelo passes that container
name as `enroll --task`, so the task joins as `@porelo-<project>_<task>.<login>`. The transport's `members` then
counts it as a person or other, and a rehearsal that waits on the project's running tasks finds none. A task made
before keeps its name, which is already the contract's.

## Plan

- `TaskConversations.enroll` and `retire` pass `ContainerName.of(project, task)` (the project's and the task's own
  names, never their short spellings) to the transport; the container stays Porelo's own identity everywhere else.
  Retire passes the name the task was enrolled under: kept beside its address in the addresses file at enroll, so a
  rename of the scheme later cannot orphan an account.
- Where a transport answers a task by that name (`members`' `task`, a sender's account), it is resolved to the task
  through the one identity lookup that takes the old name and the new one.
- Red first: a `TransportConversationsTest` case starts a task named `porelo-p_unit` and expects the stand-in
  transport's `enroll` to get `--task porelo-p-unit`, and `retire` the same.
- Changelog entry under *Fixed*.

## Acceptance

- The unit test above, seen red first.
- On a host, a rehearsal that counts the project's running tasks in the room counts the new ones.

## To be checked

- Whether the addresses file and the display names file are keyed by container today, and stay so.
