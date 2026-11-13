# B735 — A Host's Task Record Is One Project's

```json
{
  "$schema": "issue-1.json",
  "id": "B735",
  "title": "A Host's Task Record Is One Project's",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 50,
  "blockedBy": [],
  "covers": "The host keeps a person's task record and forge token by project and task, so ending one project's task never ends another project's task of the same name: `tasks/<login>/<project>/<task>.json` and the token `porelo-task-<project>_<task>`.",
  "openQuestions": 0
}
```

**Why this size.** S: the host's `HostTasks` start, end and record walk, the two calls of `HostPerson` and their
callers, tests in `host` and `apps/app`, and the host document. Estimate: 45 minutes (Core's median for S).

**What must be true.** One person's task `shell` of project `a` and task `shell` of project `b` on one host each keep
their own host record and forge token; ending one leaves the other running with both.

Read at the stack's head: `HostTasks.start(person, task, ...)` learns the project from the task's certificate, yet
writes the record `/var/lib/porelo-host/tasks/<login>/<task>.json` and issues the forge token `porelo-task-<task>`,
dropping any token of that name first. `HostTasks.end(person, task)` deletes the record and drops the token by the
task name alone. On the person's side, `HostPerson.start(task, certificate)` and `HostPerson.end(task)` send the task
name only; `TaskLaunch` calls start with `request.task()` and `TaskControl` calls end with `identity.task()`. So a
second project's start of `shell` replaces the first one's token, and either's end drops the other's record and
token.

## Plan

- `HostTasks`: the record is `tasks/<login>/<project>/<task>.json`, the project taken from the certificate at start;
  the token is `porelo-task-<project>_<task>` (neither name may hold `_`). The record walk reads both layouts.
- `end` takes the project and task; `HostPerson.end(project, task)` sends both, and `TaskControl` passes the
  identity's project. A record under the old layout is ended by the old name as before; whether running hosts' old records are moved
  is decided under [B246](README.md#b246), not here.
- The host document and man page name the new path and token; porelo-host's checks follow them.
- First red, in `host`: two projects' tasks `shell` of one person; the end of one leaves the other's record and
  token, and the start of the second does not drop the first's token.

## Acceptance

- Two projects' tasks of one name and one person on a host keep their records and tokens through each other's start
  and end.
- A task recorded under the old layout is still listed and ended.

## Built

- `HostTasks` records a task at `tasks/<login>/<project>/<task>.json`, the project from its certificate (an
  uncertified one under `default`), and issues and drops the token `porelo-task-<project>_<task>`; a start drops only
  its own name. The record walk reads both layouts; an old record keeps its old token, and a record under a project
  directory must be that project's.
- The host's `task end <project> <task>` ends the record under that project, else an uncertified one under
  `default`, else an old-layout record whose certificate names that project; ending one project's task never drops
  another project's record or token. `HostPerson.end` sends the project, which `TaskControl` takes from the task's
  identity. The sweep drops each record's own token.
- Old records are listed, shown, swept and ended, never moved.
- Red first in `HostTaskAccountsTest`: two projects' tasks `shell` of one person through each other's start and end
  (the second start was refused before), and an old-layout record listed and ended by its old token.
