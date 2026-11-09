# [B573](README.md#b573) — A Task's Project Reads Held To Its Own Project

**Status:** Idea. Filed 2026-11-09 from a code reading of the MCP task socket.
**Severity:** high - a task granted one of these reads reads another of the person's projects, its pushes waiting at
the gate among them.

**What must be true.** Over its MCP socket a task reads only its own project: each of the seven Tasks1 methods below
that its signed project file grants answers for the task's own project, and naming another, or none, is refused with
`ProjectNotItsOwn` naming both, as `Start` and every other method that takes a project already are.

- `Backups`, `ProviderModels`, `Egress`, `Pending`, `Review`, `Peers` and `MessageMembers` each take a `project`, and
  a task's socket passes it on unchecked. `Peers` holds its `task` to the caller's own already; its `project` is not
  held.
- Task lists (`List`, `Watch`) show a task only its own project's tasks, and every other method taking a project is
  held to the task's own project by the task socket, a method added later included; these seven are named as the
  exceptions in `McpTaskSockets.ANY_PROJECT`.

## Why

A person grants a task reading methods in the project file of one project. A task granted `Pending` or `Review` in it
reads the pushes another project's tasks made, waiting at that project's gate, and `Egress` and `Backups` say what
another project reaches and keeps; none of that is the granting project's. Holding them changes what an MCP client
over a task's socket may ask, so the clients' side agrees first.

## Acceptance

- Each of the seven, called over a task's socket naming another project or none, is refused with
  `ProjectNotItsOwn`, and naming the task's own project answers as before; a test fails while any one is still listed
  as an exception.
- `ANY_PROJECT` holds only `Start`, which checks its project itself.

## Open questions

- Is any of the seven meant to read across the person's projects from a task, and so kept as an exception with the
  reason beside it?
