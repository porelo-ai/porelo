# B731 — A Start Without A Homeserver Has No Conversation

```json
{
  "$schema": "issue-1.json",
  "id": "B731",
  "title": "A Start Without A Homeserver Has No Conversation",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A start is never refused for a missing homeserver or room: a project of the organization that names neither starts its task with no conversation, and one that names a homeserver still gets its room; this reverses the earlier refusal.",
  "openQuestions": 0
}
```

**Why this size.** The start's check gives way to a task without a conversation, every place that asks for the project's transport settings takes "none" as no conversation, the documentation and [B636](README.md#b636)'s decision are corrected, and the tests. Estimate: 45 minutes (Core's median for S).

A start of a discussion shell for a project of the organization was refused with 78 no-homeserver, and nothing was
created. On a person's machine the placed record of an organization project holds no homeserver and no room unless a
host's file gives them (`ProjectTaking.fromTheOrganization`). `ProjectTaking.take` asks `ProjectMembers.settings` for
every conversation of the project, and that throws `Withheld` when the record holds no homeserver, so every start of
such a project is refused.

Decided: a start is never refused for a missing homeserver or room. Without one, the task starts and has no
conversation; its agents cannot talk to each other, and that is all. The project `default`'s agents never talk to each
other, so for it this holds always. This reverses the refusal [B636](README.md#b636) decided.

## Plan

- `ProjectTaking.take` no longer refuses with 78 `no-homeserver`.
- A project of the organization with no homeserver from either place has no conversation: its task starts, no transport is set up for it, no account's own homeserver is started in its place, and the start says once that the task has no conversation and why.
- Every other caller of `ProjectMembers.settings` that would refuse or fail for this case takes it as "no conversation" (task start, room members, the machine's transports, clearing).
- A project with a homeserver, from its file or the host's, gets its room as today.
- doc/project-file.md (the no-homeserver paragraph) and [B636](README.md#b636) say the new rule.

## Acceptance

- Red first, unit: a start of an organization project with homeserver null and room null passes `take()` and starts with no conversation.
- One with a homeserver still gets its room.
- `default` starts with no conversation as today.
- The start's words name that the task has no conversation, once, and no transport process is started for it.

## Built

- `ProjectMembers.NoHomeserver` (app-model), a kind of `Withheld`: what `settings` throws for a project of the
  organization with no homeserver from its file or the host's. An unreadable placed record stays a plain `Withheld`.
- `ProjectTaking.take` lets `NoHomeserver` pass; every other `Withheld` still refuses with 78.
- `TaskConversations` looks for it before any transport is found, set up or enrolled: the start says once
  `No conversation: <project> names no homeserver for <scheme>, so this task's agents cannot talk to each other` and
  goes on. Retiring and leaving skip such a conversation.
- `Clearing` names no conversation for it, so clearing the project or the account reports nothing as not removed.
  `RoomMembers` answers `NO_ROOM` before it looks for a transport.
- doc/project-file.md: a project of the organization without a homeserver starts with no conversation; naming one
  restores the room. [B636](README.md#b636) says it is superseded. Changelog: start-without-homeserver.yml.
- Red first: `ProjectTakingTest` (two), `TaskConversationsTest`, `ClearingTest` and `TransportRoomMembersTest` each
  failed before their change.
- Left as is: a task enrolled while its project had a homeserver, whose homeserver is removed later, is skipped at
  retiring and leaving. `MessageWatch` still counts such a task as a conversation member.
