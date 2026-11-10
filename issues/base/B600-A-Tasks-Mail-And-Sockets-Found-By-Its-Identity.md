# B600 — A Task's Mail And Sockets Found By Its Identity

```json
{
  "$schema": "issue-1.json",
  "id": "B600",
  "title": "A Task's Mail And Sockets Found By Its Identity",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 5,
  "blockedBy": ["B596", "B598"],
  "covers": "Mail, watches and sockets are reached through a task's identity."
}
```

**Why this size.** messaging and daemon consumers: mail, transports, watches, sockets, parent and child.

In Review since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Messaging and the daemon (mailboxes, transport accounts, clearing, watches, socket validation, parent and child, completion) find a task through its identity: old and new tasks in different projects receive only their own mail, an underscore endpoint opens, and no caller can stop, read or answer for a sibling.

## Plan

[B538](README.md#b538)'s plan, slice 4: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- Old and new tasks get only their own mail and sockets, across a daemon restart.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.

## As built

Messaging addresses the verified logical task while keeping mailboxes, transport accounts, saved
names, signatures and credentials under the actual container name. Direct recipients, person
mentions, acting-account aliases, rejected-work feedback, default-project signing principals and
sibling watches use that separation for legacy and encoded tasks. An unscoped `talk pass` accepts
an underscore container name and finds its recorded project. Missing ownership never selects a
similarly spelled mailbox. A rejection with no remaining task still succeeds without notifying one.

Project transport clearing removes exact task entries whose identity or retained reservation proves
the project, including removed tasks, and keeps another project's entries even where names overlap.
It takes the account name lock before the vault update. Enrollment before container creation uses
the committed reservation's logical task. Retirement after ordinary records are deleted, and
retirement of a pending start, likewise keep that reservation's project; ordinary message delivery
and resume do not gain that cleanup fallback.

MCP endpoints accept encoded names, verify their project, preserve an endpoint already served by
the daemon, and can be reopened at the same actual path by a replacement daemon. Parent and child
checks verify both identities and the child's recorded parent. Starts select the existing actual
child before checking its profile and counting it; a different project's child is refused even if
its profile names the caller. A task can declare completion only for its own verified endpoint.
Task-socket listings omit unrecognized ownership, and listed rows also require verified ownership.
Clearance watcher replacement retains the complete logical task, including numeric suffixes.

The shared lookup also reads durable records when resolving a logical name without a runtime row.
An incomplete retained record can block an ambiguous selection, but cannot authorize an action.
The reservation API exposes retained logical ownership for cleanup without changing its format or
lock order. No gate, task-secret, certification or ordinary task-removal implementation was changed.

Each part went red first: dropped encoded mail, a lost logical person mention, enrollment before an
id exists, cross-project read and stop authorization, cleanup after record deletion, missing-task
rejection and incomplete retained ownership. The socket integration test checks private permissions,
the addressed endpoint after daemon recreation and a served inode kept on a repeated open.

Two answers change. `talk pass` with a name no task holds says "no task has that name" (exit 66)
instead of asking for `--project` (exit 2): a project is no longer read out of a name. And because a
repeated open keeps the served socket (a running task's mount must not lose its inode), a resumed
task keeps the methods its project file allowed when its socket was first opened, until the daemon
restarts; picking up changed methods in place is left for a later issue. A reserved
`login-<digits>` name stays usable only while its legacy container exists; a retained record of a
removed one does not keep it. Fixtures that listed a task without the id a real start records
were given that id.

Creation still uses legacy names. Enabling compact allocation, including the MCP new-child
allocation, remains for [B601](README.md#b601).
