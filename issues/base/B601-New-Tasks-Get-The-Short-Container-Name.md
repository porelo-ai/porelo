# B601 — New Tasks Get The Short Container Name

```json
{
  "$schema": "issue-1.json",
  "id": "B601",
  "title": "New Tasks Get The Short Container Name",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 6,
  "blockedBy": ["B597", "B598", "B599", "B600"],
  "covers": "New tasks are created under the new container names."
}
```

**Why this size.** switching creation to the new names, for new tasks only.

In Review since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A new task of repository `porelo-pi` in project `porelo` is container `porelo-pi`; a task of project `pi` is `porelo-pi_x` (or its short form). The CLI, the dry run, `CanStart` and the daemon give the same name; an existing `porelo-porelo-pi` is found and resumed, never made again. Acceptance helpers, examples and contract comments that build old names follow.

## Plan

[B538](README.md#b538)'s plan, slice 5: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- New tasks get the new names; tasks made before are found under theirs.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.

## As built

A new task is made under the new name. `TaskRunner.containerName` encodes it with `ContainerName.encoded`, using
the project's `short`, the start's `--short` and the account's uid for the socket budget. A task of project
`porelo` is `porelo-<task>`, any other is `porelo-<project>_<task>`. The launch's one name choice
(`startContainer`) looks the logical task up first, so a task made before is found and brought back under the name
it was made with. The CLI, the dry run, the daemon's Start and an MCP child start all go through that choice: the
MCP child start now reads the project's short spelling and uses the same encoder for its starts in flight. Whether
a task exists, for keeping its branch, uses the same lookup. The free-name search for waiting work tests both the
old and the new spelling. A name no task can have still meets the start's own refusal first. `CanStart` asks the
same name check as before.

The name-length check keeps its old limit, `porelo-<project>-<task>` at most 64 characters. For every project it is
at least as strict as the new form, so a name it passes always fits; the shorter forms do not yet buy longer task
names. A failed start kept before the upgrade holds a reservation under the old name; a retry by logical name
does not find that reservation, and it is refused naming its owner. No such attempt is known on a machine, and
removing it frees the name.

The tests went red first (log kept under the supervisor's scratchpad): a project's new task is
`porelo-uc_shell`, project `porelo`'s is `porelo-pi`, short spellings give `porelo-ws_x`. Two guards passed
before and after: a task made as `porelo-porelo-pi` is brought back under that name with nothing created, and a
dry run claims no name. The unit fixtures that start a task now read its files under the new name. Two fixtures
used the old encoding's overlap: one now uses two projects with the same short name; the other records the new
task it starts.

Acceptance helpers, examples and contract comments follow. Codex renamed every container a scenario makes across
26 acceptance files; I reviewed the diff. The prune scenario's synthetic September state keeps its old name. The
features run on a machine only, which [B602](README.md#b602)'s night run on the local VM covers. The two
`15-task.varlink` comments, `doc/commands.md` and the getting-started example name the new form.
