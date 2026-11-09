# [B583](README.md#b583) — The Room's Members Over The Daemon

**Status:** Backlog. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** S - one transport verb read through `describe`, one Tasks1 method, its interface text.

**What must be true.** A client of porelod can ask who is in a project's room: each member's shown name, whether it
joined or is invited, and whether it is a task (with its container), a person or a helper, so the window's room dialog
lists who is in instead of "Nobody has joined yet".

## Plan

The transport announces a lifecycle verb `members --project P` in `describe`, run like `join`/`part` with the
account's secrets on standard input; it answers `{"room":…,"members":[{"id","shown","state":"join|invite",
"kind":"task|person|helper","task":"<container>" for a task}]}`, `"members":[]` when there is no room yet, exit 75
when the homeserver is not reached and 78 for settings or secrets (agreed with porelo-message-matrix).

- `TransportDescription` reads the verb; `TransportLifecycle` runs it as it runs `join` and judges the answer (sizes,
  states, kinds, a `task` only on a task, the container one of this project's).
- A Tasks1 method `RoomMembers(project)` answers it, with typed errors for no transport, no room and an unreachable
  homeserver; its interface text and doc/messages.md.
- Red first: a fake transport's answer is passed on judged; an answer naming a container of another project is
  refused.
