# B602 — A Task Made Before Comes Through The Upgrade

```json
{
  "$schema": "issue-1.json",
  "id": "B602",
  "title": "A Task Made Before Comes Through The Upgrade",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 7,
  "blockedBy": ["B601"],
  "covers": "A task made before the change survives the upgrade whole."
}
```

**Why this size.** one real-machine upgrade scenario and the operating notes.

Implementing since 2026-11-10 (`porelo-project` ST307; the scenario first, its run after [B601](README.md#b601)). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** A kept task made by the release before this change keeps its container, id, workspace, session, mail, credential route and MCP child through an upgrade of the CLI, daemon and hooks together, a daemon restart, a stop and a start (the operator, 2026-11-10: existing names stay for their lifetime; the three upgrade together). A new task started after it gets the new name.

## Plan

[B538](README.md#b538)'s plan, slice 7: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- The upgrade scenario passes on a real machine against a package from before the change.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
