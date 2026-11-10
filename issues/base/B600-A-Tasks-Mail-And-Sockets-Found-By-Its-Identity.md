# B600 — A Task's Mail And Sockets Found By Its Identity

```json
{
  "$schema": "issue-1.json",
  "id": "B600",
  "title": "A Task's Mail And Sockets Found By Its Identity",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 5,
  "blockedBy": ["B596", "B598"],
  "covers": "Mail, watches and sockets are reached through a task's identity."
}
```

**Why this size.** messaging and daemon consumers: mail, transports, watches, sockets, parent and child.

Implementing since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Messaging and the daemon (mailboxes, transport accounts, clearing, watches, socket validation, parent and child, completion) find a task through its identity: old and new tasks in different projects receive only their own mail, an underscore endpoint opens, and no caller can stop, read or answer for a sibling.

## Plan

[B538](README.md#b538)'s plan, slice 4: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- Old and new tasks get only their own mail and sockets, across a daemon restart.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
