# [B519](README.md#b519) — A Person's Line On A Host Reaches The Tasks

**Status:** Backlog. Filed 2026-11-08 from the third move. The operator said to file it (19:02Z). Not built tonight.
**Size:** S - one condition in `apps/app-messaging`, with a test seen red first.

**What must be true.** On a host, a person admitted to a project has their line in the project's room handed to the
project's tasks, and a task they mention is woken.
- `TransportLifecycle.persons` answers the project's admitted logins (`ProjectMembers.admitted`) whenever the
  transport takes `persons` and a host placed the project (`ProjectMembers.listed`). It no longer asks for the
  lifecycle verb `members`.
- A person's file whose `account` is an admitted login is taken as that person, as `PersonsWords.read` already does once
  `persons` holds them.
- A login that is not admitted is still dropped with "… who is not in the project's conversation".

## Why

In the third move (porelo-move-3, porelo 448), the operator joined porelo's room through the host's gate by SSO as
`@michael-schnell` and mentioned each of the three tasks. No task was woken. The person's porelod dropped every line:

```
porelod: dropped 20261108T185346863--matrix-person-87acf78b-….json: it is from @michael-schnell:matrix.porelo-move-3.porelo.internal, who is not in the project's conversation
```

`TransportLifecycle.java:367` answers the admitted logins only when the transport offers the lifecycle verb `members`.
The Matrix transport stopped offering it on 2026-11-02 (porelo-message-matrix 4d8bb16, "Keep a project's room from the
host, as the administrator"), since the host's `keep-room --persons` now keeps the room's persons. So on every host
`persons` is empty. A person's line is never taken by its `account`, and `talk join` ends in 77 there by design. Today no
person's words reach a task on a host.

`porelo-message-transport-matrix describe` on the host shows it: `"persons": true`, and no `"members"` in `lifecycle`.

## Plan

- A test in `apps/app` (the messaging tests' module) has a transport describing `persons` and no `members`, a project
  placed with an admitted login, and a person's file with that `account`. It is seen red against main: the file is
  dropped.
- `TransportLifecycle.persons`: `described.takes(PERSONS) && ProjectMembers.listed(context, project)` in place of
  `described.offers("members") && described.takes(PERSONS)`.
- Agent Matrix needs nothing; no MX Idea.
