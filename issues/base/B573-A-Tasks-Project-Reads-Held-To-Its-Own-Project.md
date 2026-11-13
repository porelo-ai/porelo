# B573 — A Task's Project Reads Held To Its Own Project

```json
{
  "$schema": "issue-1.json",
  "id": "B573",
  "title": "A Task's Project Reads Held To Its Own Project",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task's MCP socket holds `Backups`, `ProviderModels`, `Egress`, `Pending`, `Review`, `Peers` and `MessageMembers` to the task's own project, as every other method taking a project. Severity high.",
  "openQuestions": 0
}
```

**Why this size.** one module, the daemon's task socket, and its test; the refusal already exists.

Filed 2026-11-09 from a code reading of the MCP task socket; accepted by the operator on
2026-11-09 with no exception.

**Severity.** high - a task granted one of these reads reads another of the person's projects, its pushes waiting at
the gate among them.

**What must be true.** Over its MCP socket a task reads only its own project: each of the seven Tasks1 methods below
that its signed project file grants answers for the task's own project, and naming another, or none, is refused with
`ProjectNotItsOwn` naming both, as `Start` and every other method that takes a project already are.

- `Backups`, `ProviderModels`, `Egress`, `Pending`, `Review`, `Peers` and `MessageMembers` each take a `project`, and
  a task's socket passes it on unchecked. `Peers` holds its `task` to the caller's own already; its `project` is not
  held.
- None of the seven reads across the person's projects from a task: each is held to the task's own project, with no
  exception, and only `Start`, which checks its project itself, is left out of the general hold.
- Task lists (`List`, `Watch`) show a task only its own project's tasks, and every other method taking a project is
  held to the task's own project by the task socket, a method added later included; today these seven are named as
  the exceptions in `McpTaskSockets.ANY_PROJECT`.

## Why

A person grants a task reading methods in the project file of one project. A task granted `Pending` or `Review` in it
reads the pushes another project's tasks made, waiting at that project's gate, and `Egress` and `Backups` say what
another project reaches and keeps; none of that is the granting project's. Holding them changes what an MCP client
over a task's socket may ask; the operator decided it, and porelo-mcp is told.

## Acceptance

- Each of the seven, called over a task's socket naming another project or none, is refused with
  `ProjectNotItsOwn`, and naming the task's own project answers as before; a test fails while any one is still listed
  as an exception.
- `ANY_PROJECT` holds only `Start`, which checks its project itself.

## Plan

- `daemon/.../McpTaskSockets.java`: `ANY_PROJECT` keeps only `Start`, so `takingAProject()` holds the seven through the
  branch of `TaskSocket.method` that already refuses with `ProjectNotItsOwn`.
- `Peers` takes a task as well, and `TaskSocket.method` tries the task branch first, so it never reaches the project
  hold: a method in both sets gets both checks, the task's and the project's.
- Red first: in `McpTaskSocketsTest.aMethodTakingAProjectReadsOnlyTheCallersOwnProject`, a socket allowing the seven
  is called for each naming another project and none; each is answered today and must be refused. The set assertion
  turns to `takingAProject()` containing the seven, `ANY_PROJECT` equal to `{Start}`.
- `doc/` names nothing of which methods a task's socket holds to its project (checked when building); the changelog
  gets a `security` entry, since what a task can read narrows.
