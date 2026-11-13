# B246 — A Task Name No Other Project Shares

```json
{
  "$schema": "issue-1.json",
  "id": "B246",
  "title": "A Task Name No Other Project Shares",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "A task's container name is one project's and one task's: new tasks are named `porelo-<project>_<task>` already; what is left is the code that still reads a project or task out of a name, the host's records kept by task name alone, and the docs and stories on the old form; project `porelo`'s tasks are `porelo_<task>`, and what assumes a `porelo-` beginning takes both",
  "openQuestions": 0
}
```

**What must be true.** A task's container name belongs to one project and one task, so no task of another project
ever has it - not while it runs, and not afterwards, when what Porelo keeps by that name is read again.

## Why

`ContainerName.of` joins the two with a hyphen, `porelo-<project>-<task>`, and both names may hold hyphens: project
`a-b` with task `c` and project `a` with task `b-c` are both `porelo-a-b-c`. While one runs, a start of the other is
refused (`TaskInventory.ofAnotherProject`: "the two names meet"). But Porelo keys more than the container by that
name - the vault's `task/<container>/` entries (route tokens, a transport's task secrets), the state directory, the
clearance decisions, the mailbox - and a transport keys a task's account by the `--task` it is given, which is the
container's name. What of it outlives a removal is read below.

**Why this size.** M, at its upper end: the separator is built; what is left touches the task start, the transport's enrollment,
the host's task records (a record's key and a token's name on the host), docs and four acceptance stories, with
tests in each. Estimate: 63 minutes (Core's median for M), p80 229.

## Read in today's code

- **The separator is built.** `ContainerName.encoded` names a new task `porelo-<project>_<task>` (or the short
  spellings), and `porelo-<task>` in project `porelo`; `TaskRunner.containerName` makes every start's name through
  it. Neither name pattern (`Project.NAME_PATTERN`, `TASK_NAME_PATTERN`, `ShortName`) allows `_`, and podman does.
  A task made before keeps its `porelo-<project>-<task>` name and is found through its recorded identity.
- **What outlives a removal is held by the name's claim.** `state/task-identities/<container>.json` is never deleted;
  a retired claim refuses every other project and task (`TaskReservations` claim and `retire`), as do a leftover
  `task/<container>/` vault scope and retained state. So nothing kept by a removed task's container name reaches
  another project's task today; the cost is that a removed name is never free again.
- **Still read out of a name**, against the rule that a name is never parsed for its project or task:
  - `TaskRunCommand` takes the task of an existing container by cutting `porelo-<project>-` off its name: wrong
    with a project's short name, and out of bounds for project `porelo`'s `porelo-<task>`.
  - `TransportLifecycle.contractName` passes a container on as already in the contract's form when it starts with
    `porelo-<project>-`, which a new name whose project short name begins `<project>-` also does.
  - `ContainerName.taskFrom` and `taskIn` (legacy shape only) in `StartCheck`, `TaskCertifyCommand` and the
    reattached window's label.
- **The host keeps a task by person and task name, with no project**: `HostTasks` writes
  `tasks/<login>/<task>.json` and the forge token `porelo-task-<task>`, and `end(task)` drops both. Removing one
  project's task `shell` ends the record and token of another project's running `shell` of the same person.
- **The transport's contract name is still `porelo-<project>-<task>`** (on purpose, so a room reads a task by it):
  `a-b`/`c` and `a`/`b-c` hand the adapter the same `--task` with a different `--project`.
- **Stale docs**: `doc/project-file.md` (the container's name and the length rule), `doc/commands.md`'s length line
  and `15-task.varlink`'s. **Stories on the old form**: `st39`, `st43`, `st53` (a directory made under the old name,
  so its obstruction misses a new task) and `st56`.

## Plan

- `TaskRunCommand`: an existing container's task comes from its identity (`TaskIdentities.lookup`'s task), never
  from its name. First red in `TaskRunCommandTest`: a start outside a checkout naming a stopped task of project
  `porelo`, and one of a project with a short name, brings that task back (today the first throws).
- `TransportLifecycle.contractName`: the contract's task comes from the enrolled identity alone; a red test with a
  project short name `<project>-a`.
- `StartCheck`, `TaskCertifyCommand` and the window label read the task from the identity or the labels;
  `taskFrom`/`taskIn` keep only the legacy fallback where no identity exists.
- Project `porelo` takes the separator too, shortened where the two first words are the same (the operator,
  2026-11-13): its new task is `porelo_<task>` (`porelo_pi`), every other project's `porelo-<project>_<task>` or
  `porelo-<short>_<task>`. `porelo_` comes only from project `porelo`, and old names hold no `_`, so the forms stay
  disjoint. `ContainerName.encoded` makes it; its special form `porelo-<task>` and the reservation of
  `login-<digits>` for project `porelo`'s new tasks go, since `porelo_login-1` cannot meet a login container.
  A task made before keeps its name through its identity. Red: a new task `pi` of project `porelo` is `porelo_pi`.
- What assumes every task container begins `porelo-` takes both beginnings, through one predicate and one pattern
  in `ContainerName` (`isPorelo`, and a `NAME_PATTERN` for `porelo[-_][a-z0-9_-]+`) in place of the inline ones.
  Read on 2026-11-13:
  - `ContainerName.isPorelo` (`startsWith(PREFIX)`), and through it `isTask`, with 35 callers: the inventory,
    cleanup, task list, attach and stop. A `porelo_` task would be no task at all.
  - The inline pattern `porelo-[a-z0-9_-]+`: `TaskIdentities` (4 places), `TaskHistory`, `TaskReservations`,
    `TaskTarget`, `TransportLifecycle` (2), `TalkPassCommand`, `TaskMethods` (daemon), and `McpTaskSockets`'
    socket name pattern.
  - `startsWith(ContainerName.PREFIX)` on a request's task: `TaskLaunch` and `McpTaskSockets`; and
    `TaskLogsCommand`'s check of a spelled name.
  - The acceptance kit's `LoopWatch` (`podman ps --filter name=^porelo-`) and `ReadySteps`' expected name.
  - Not affected: the host's records and tokens (`porelo-<login>` is a forge identity, not a container), the
    SELinux module (types, no names), helper ids (`porelo-<uuid>`), and the person-card login checks.
  - The transport's contract name stays `porelo-<project>-<task>` (`ContainerName.contract`), as decided.
  Red: a `porelo_pi` container is listed, attached, stopped, cleaned up and given its MCP socket.
- A guard test keeps it so (the operator, 2026-11-13): it reads the main sources of every module and the acceptance
  kit, and fails on a container-name pattern beginning `porelo-` or `porelo[`, or a `startsWith` on
  `"porelo-"` or `ContainerName.PREFIX`, anywhere outside `ContainerName`. The names that are no container's (a
  forge identity `porelo-<login>`, a helper id, a package file name) are listed in the test by file and reason, so
  a new one is a decision, not a habit.
- The host: a task's record and forge token kept by project and task is built by [B735](README.md#b735). Records
  and tokens under the old key are left to end with their tasks, and new tasks use the new key (the operator,
  2026-11-13). Nothing is moved.
- Docs and the four stories on the new form; `ContainerName.of` stays only as the contract's name.

## Acceptance

- No code path takes a project or task out of a container's name where an identity or a label names them; a test
  per place above, seen red first.
- Two projects' tasks of one name and one person on a host each keep their record and token through the other's end.
- Project `porelo`'s task made before as `porelo-<task>` and a short-named project's task are brought back by name
  outside a checkout.
- A new task of project `porelo` is `porelo_<task>`; every other new task's name is `porelo-<project or short>_<task>`.
- No code path takes a task container to begin with `porelo-` alone; `porelo_pi` is a task everywhere one is.
- One function in `ContainerName` decides whether a name is Porelo's and a task's; the guard test fails on any
  container-name pattern or prefix check outside it.

## Answered

- Project `porelo` takes the separator too, its two first words shortened to one: `porelo_<task>` (the operator,
  2026-11-13).
- The host's records and forge tokens under the old key are left to end with their tasks, while new tasks use the
  new key, as [B735](README.md#b735) builds it (the operator, 2026-11-13).

## To be checked

- Whether the transport adapters key a task's account by `--project` and `--task` together, as the contract says;
  if one keys by `--task` alone, `a-b`/`c` and `a`/`b-c` share an account there.
