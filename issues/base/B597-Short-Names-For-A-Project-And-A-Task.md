# B597 — Short Names For A Project And A Task

```json
{
  "$schema": "issue-1.json",
  "id": "B597",
  "title": "Short Names For A Project And A Task",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["B596"],
  "covers": "`project.short` and a task's `--short` shorten its container name."
}
```

**Why this size.** the project file's `project.short`, a start's `--short`, the wire field and the codec's use of them.

In Review since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A project may name a short name (`project.short`) and a start may give a task one (`--short`, `short` on Tasks1 `Start` and `CanStart`): lower case `[a-z][a-z0-9-]*`, at most 16 characters, no underscore, no `porelo-` prefix. The codec builds `porelo-<project short>_<task short>`, each missing one falling back to the full name; project `porelo` keeps `porelo-<task short or task>`. The task's short name is recorded with its identity and never changes; a later `project.short` changes only tasks made after it.

## Plan

[B538](README.md#b538)'s plan, slice 1b ("Short names"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- A wrong short name is refused naming its key; the codec gives the short form and falls back per missing field.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.

## As built

The project reader and project-file check accept optional `project.short`, and the project-file writer and
schema retain and describe it. One validator checks both project and task spellings: lowercase letters,
digits and hyphens, beginning with a letter, at most 16 characters, without underscore or the `porelo-`
prefix. The command line accepts `--short`; Tasks1 Start and CanStart accept optional `short` and pass it to
the shared request and validation code. Invalid spellings are refused before start state is made.

The compact codec takes both optional spellings and falls back independently to each logical name.
Project `porelo` keeps its single-component form. Socket budgets and explicit-name refusals remain in force.
The sidecar records the logical task and both original spellings and is saved with the existing durable task
state. Identity lookup checks these records against the actual name and recorded id, including after the
project file changes or runtime state is lost. Older sidecars and legacy containers remain readable.

Container creation still uses legacy names. Atomic name reservations and the refusal of a second project
using the same short spelling remain for [B598](README.md#b598); enabling compact names for new containers
remains for [B601](README.md#b601). No reservation, rename or migration is added here.

The reader, codec, sidecar, start and identity lookup, the daemon's Start and CanStart and the contract
have unit cases that failed before the change. A short spelling that does not verify against the recorded
identity never authorizes an action; it only makes a selection ambiguous. A start or preflight that would
change an existing task's recorded spelling is refused, naming a new task as the way out.
