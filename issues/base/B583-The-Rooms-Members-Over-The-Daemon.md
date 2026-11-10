# B583 — The Room's Members Over The Daemon

```json
{
  "$schema": "issue-1.json",
  "id": "B583",
  "title": "The Room's Members Over The Daemon",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 18,
  "blockedBy": [],
  "covers": "`RoomMembers` over the daemon from the transport's `members` verb: who is in a project's room, tasks by container."
}
```

**Why this size.** one transport verb read through `describe`, one Tasks1 method, its interface text.

Filed 2026-11-09 under ST290, from the fourth move.

**What must be true.** A client of porelod can ask who is in a project's room: each member's shown name, whether it
joined or is invited, and whether it is a task (with its container), a person or a helper, so the window's room dialog
lists who is in instead of "Nobody has joined yet".

## Plan

The transport announces a lifecycle verb `members --project P` in `describe`, run like `join`/`part` with the
account's secrets on standard input; it answers `{"room":…,"members":[{"id","shown","state":"join|invite",
"kind":"task|person|helper","task":"<container>" for a task}]}`, `"members":[]` when there is no room or no setup yet (`kind` may also be `other`, never taken as a task; on a central homeserver a task member carries `login`, and porelo takes only its own), exit 75
when the homeserver is not reached and 78 for settings or secrets (agreed with porelo-message-matrix).

- `TransportDescription` reads the verb; `TransportLifecycle` runs it as it runs `join` and judges the answer (sizes,
  states, kinds, a `task` only on a task, the container one of this project's).
- A Tasks1 method `RoomMembers(project)` answers it, with typed errors for no transport, no room and an unreachable
  homeserver; its interface text and doc/messages.md.
- Red first: a fake transport's answer is passed on judged; an answer naming a container of another project is
  refused.

## As built

`RoomMembers(project)` is registered by `MessagingMethods` in Tasks1's messaging part. `RoomMembers` in
`app-messaging` selects the project's conversation and maps named failures; `TransportLifecycle` runs the
advertised `members --project` verb with settings and account/project secrets on stdin, requires that input
capability, and judges its answer before projecting member records. It neither runs setup nor saves the answer.

The answer is bounded to 1 MiB and 1,024 members, with bounded strings, `join`/`invite` states and
`task`/`person`/`helper`/`other` kinds. Only a task may carry a container. Host inventory establishes that the
container belongs to this project, including recorded container identity; names and prefixes alone establish
nothing. A transport taking `login` must name each task member's login, and only this account's tasks are kept.
Invocation and judgment use the same capability snapshot. Inventory is asked once, only when a task of this
account needs verification; a room containing only people and helpers does not depend on the container runtime.
Duplicate member ids and task containers, malformed fields, and supplied credentials echoed in successful
answers are refused without repeating the value. The relation between a member id and its container remains a
transport claim for display; it authorizes neither message delivery nor any act on that container.

No supporting transport is `NoTransport`; no declared or set-up room is `NoRoom`; exit 75 is
`HomeserverUnreachable`. Settings/secrets failures (78) and invalid answers (76) are `ConversationRefused`.
An existing room with no members is a successful empty snapshot. MCP classifies this method as `NEVER`:
the operator's room dialog may read server-provided display names through the host's credentials, while a task
may not use this query to bypass its filtered message boundary. Task reach is unchanged.

`TransportRoomMembersTest` covers the judged fake answer, project ownership, login ownership, malformed and
oversized answers, credentials on stdin, and credential echoes. `RoomMembersTest` covers the daemon adapter's
typed errors and MCP policy; its socket case exercises method registration where the environment allows it.
`InterfaceDescriptionTest` holds the signature, member fields and typed errors to the description.

The code reading's two findings each have a regression seen failing before the fix: people-only answers no
longer invoke Podman, and changing descriptions cannot give invocation and ownership judgment different login
semantics. Both fixes stay in the messaging area.
