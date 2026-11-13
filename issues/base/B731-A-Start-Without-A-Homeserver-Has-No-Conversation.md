# B731 — A Start Without A Homeserver Has No Conversation

```json
{
  "$schema": "issue-1.json",
  "id": "B731",
  "title": "A Start Without A Homeserver Has No Conversation",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A start is never refused for a missing homeserver or room: a project of the organization that names neither starts its task with no conversation, and one that names a homeserver still gets its room; this reverses the earlier refusal.",
  "openQuestions": 0
}
```

**Why this size.** The start's check gives way to a task without a conversation, every place that asks for the project's transport settings takes "none" as no conversation, the documentation and [B636](README.md#b636)'s decision are corrected, and the tests. Estimate: 45 minutes (Core's median for S).

Operator finding on 2026-11-13, ST314's idea discussion: the window's start of a discussion shell was refused with 78, "no-homeserver: porelo names no homeserver under 'mail.transports.matrix…'", and nothing was created. On a person's machine the placed record of an organization project holds no homeserver and no room unless a host's file gives them (`ProjectTaking.fromTheOrganization`). `ProjectTaking.take` asks `ProjectMembers.settings` for every conversation of the project, and that throws `Withheld` when the record holds no homeserver, so every start of such a project is refused.

Operator decision on 2026-11-13: a start must never be refused for a missing homeserver or room. Without one, the task starts and has no conversation; its agents cannot talk to each other, and that is all. The project `default`'s agents never talk to each other, so for it this holds always. This reverses the refusal [B636](README.md#b636) decided.

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
