# B601 — New Tasks Get The Short Container Name

```json
{
  "$schema": "issue-1.json",
  "id": "B601",
  "title": "New Tasks Get The Short Container Name",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 6,
  "blockedBy": ["B597", "B598", "B599", "B600"],
  "covers": "New tasks are created under the new container names."
}
```

**Why this size.** switching creation to the new names, for new tasks only.

Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A new task of repository `porelo-pi` in project `porelo` is container `porelo-pi`; a task of project `pi` is `porelo-pi_x` (or its short form). The CLI, the dry run, `CanStart` and the daemon give the same name; an existing `porelo-porelo-pi` is found and resumed, never made again. Acceptance helpers, examples and contract comments that build old names follow.

## Plan

[B538](README.md#b538)'s plan, slice 5: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- New tasks get the new names; tasks made before are found under theirs.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
