# [B596](README.md#b596) — One Identity For A Task, Old Name Or New

**Status:** Implementing, since 2026-11-10. Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).
**Priority:** high (the operator, 2026-11-09, for [B538](README.md#b538)).
**Size:** M - the shared identity value and lookup, the legacy reader and the injective codec, with old creation kept.
**Blocked by:** -.

**What must be true.** Every command that names a task finds it through one identity - project, logical task, actual container and recorded id - whether its container was named the old way (`porelo-<project>-<task>`) or the new way (`porelo-<task>` in project `porelo`, `porelo-<project>_<task>` elsewhere). A name is never parsed back into a project by its longest prefix; `web/app-x` and `web-app/x` stay two tasks. New tasks are still created under old names until a later issue switches creation.

## Plan

[B538](README.md#b538)'s plan, slice 1 ("One identity lookup, two formats" and "Proposed encoding"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- A task is found by its identity, old and new forms alike, and no lookup confuses two projects' tasks; creation is unchanged.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
