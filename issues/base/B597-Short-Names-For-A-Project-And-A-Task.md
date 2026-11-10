# B597 — Short Names For A Project And A Task

```json
{
  "$schema": "issue-1.json",
  "id": "B597",
  "title": "Short Names For A Project And A Task",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["B596"],
  "covers": "`project.short` and a task's `--short` shorten its container name."
}
```

**Why this size.** the project file's `project.short`, a start's `--short`, the wire field and the codec's use of them.

Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A project may name a short name (`project.short`) and a start may give a task one (`--short`, `short` on Tasks1 `Start` and `CanStart`): lower case `[a-z][a-z0-9-]*`, at most 16 characters, no underscore, no `porelo-` prefix. The codec builds `porelo-<project short>_<task short>`, each missing one falling back to the full name; project `porelo` keeps `porelo-<task short or task>`. The task's short name is recorded with its identity and never changes; a later `project.short` changes only tasks made after it.

## Plan

[B538](README.md#b538)'s plan, slice 1b ("Short names"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- A wrong short name is refused naming its key; the codec gives the short form and falls back per missing field.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
