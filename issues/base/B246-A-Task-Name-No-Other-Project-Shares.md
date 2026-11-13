# B246 — A Task Name No Other Project Shares

```json
{
  "$schema": "issue-1.json",
  "id": "B246",
  "title": "A Task Name No Other Project Shares",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "A task's container name is one project's and one task's: new tasks are named `porelo-<project>_<task>` already; what is left is the code that still reads a project or task out of a name, the host's records kept by task name alone, and the docs and stories on the old form",
  "openQuestions": 2
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

**Why this size.** M: the separator is built; what is left touches the task start, the transport's enrollment,
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
- The host: a task's record and forge token are kept by project and task; `end` takes the project and drops only
  that task's. A record or token under the old key is read once and moved (see the questions). Red: two projects'
  tasks `shell` of one person, the end of one leaves the other's record and token.
- Docs and the four stories on the new form; `ContainerName.of` stays only as the contract's name.

## Acceptance

- No code path takes a project or task out of a container's name where an identity or a label names them; a test
  per place above, seen red first.
- Two projects' tasks of one name and one person on a host each keep their record and token through the other's end.
- Project `porelo`'s `porelo-<task>` and a short-named project's task are brought back by name outside a checkout.

## Open questions

- Project `porelo` keeps `porelo-<task>` with no separator (as decided for its doubled name). That name can equal a
  task made before under the old form (`porelo-web-app` is project `web`'s task `app` too); the name's claim keeps
  them apart, as it does now. Keep it so, or give project `porelo` the separator too (`porelo-porelo_<task>`)?
- The host's task records and forge tokens under the old key on hosts that run today: moved at the host's next
  update, or left to end with their tasks while new ones use the new key?

## To be checked

- Whether the transport adapters key a task's account by `--project` and `--task` together, as the contract says;
  if one keys by `--task` alone, `a-b`/`c` and `a`/`b-c` share an account there.
