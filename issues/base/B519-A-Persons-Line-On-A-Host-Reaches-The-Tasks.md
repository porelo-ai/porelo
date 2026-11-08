# [B519](README.md#b519) — A Person's Line On A Host Reaches The Tasks

**Status:** Review. Filed 2026-11-08 from the third move (the operator, 19:02Z); built the same evening on the word to work the move's findings off (19:38Z).
**Size:** S - one condition in `apps/app-messaging`, with a test seen red first.

**What must be true.** On a host, a person admitted to a project has their line in the project's room handed to the
project's tasks, and a task they mention is woken.
- `TransportLifecycle.persons` answers the project's admitted logins (`ProjectMembers.admitted`) where the transport
  takes `persons` and the room is on the host's own homeserver: the project names no homeserver of its own and the host
  placed one. Elsewhere it still asks for the lifecycle verb `members`.
- On a homeserver the project names, no person is taken by the account a transport names without `members`: anybody
  with that homeserver's registration token could make an account by an admitted login's name. On the host only the
  login's own gate makes it.
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

## As built

- `ProjectMembers.onTheHostsHomeserver(context, project, scheme)`: true for `matrix` where the project's
  `mail.transports.matrix` names no `homeserver` and the placed project carries the host's.
- `TransportLifecycle.persons` answers the admitted logins where the transport takes `persons` and either offers
  `members` or the room is on the host's homeserver.
- Tests in `MachineTransportsTest`: on the host's homeserver an admitted login's line is handed to the task and an
  unadmitted one dropped with "not in the project's conversation" (seen red against main); on a homeserver the project
  names, nobody is taken by account. `aTransportThatKeepsNoPersonsInTheRoomHasNoPersonTakenByAccount` holds unchanged.
- The plan's first idea, gating on `ProjectMembers.listed` alone, would have taken that case too; it broke that test,
  and the rule now names the host's homeserver.


After the reading:

- The host's homeserver and the admitted logins on it come from the host's file under `/etc/porelo-host` alone
  (`OrganizationProjects.host`), never from the account's own record under XDG data, which the account may write and
  which a lock-out leaves with the old homeserver and keys. `ProjectMembers.onTheHostsHomeserver` gave way to
  `ProjectMembers.admittedOnTheHostsHomeserver(projects, project, scheme)`, which answers those logins, or none.
- `TransportLifecycle.persons` still answers `ProjectMembers.admitted` where the transport offers `members`;
  `ProjectMembers.admitted`, `listed` and `requireAdmitted` are unchanged for their other callers (join, the joins heard,
  certifiers).
- `TransportLifecycle` and `TransportConversations` take the host's directory for a test; `MachineTransportsTest` places
  the host's file there. New tests: a placed file only in the account's own directory takes nobody (seen red: bob was
  taken); a transport other than `matrix` takes nobody. The declared-homeserver guard was already held by
  `onAHomeserverTheProjectNamesNoPersonIsTakenByAccountWithoutMembers`, which fails with the guard removed.
