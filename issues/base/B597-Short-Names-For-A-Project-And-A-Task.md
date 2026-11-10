# [B597](README.md#b597) — Short Names For A Project And A Task

**Status:** Backlog. Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).
**Priority:** high (the operator, 2026-11-09, for [B538](README.md#b538)).
**Size:** S - the project file's `project.short`, a start's `--short`, the wire field and the codec's use of them.
**Blocked by:** [B596](README.md#b596).

**What must be true.** A project may name a short name (`project.short`) and a start may give a task one (`--short`, `short` on Tasks1 `Start` and `CanStart`): lower case `[a-z][a-z0-9-]*`, at most 16 characters, no underscore, no `porelo-` prefix. The codec builds `porelo-<project short>_<task short>`, each missing one falling back to the full name; project `porelo` keeps `porelo-<task short or task>`. The task's short name is recorded with its identity and never changes; a later `project.short` changes only tasks made after it.

## Plan

[B538](README.md#b538)'s plan, slice 1b ("Short names"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- A wrong short name is refused naming its key; the codec gives the short form and falls back per missing field.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
