# B599 — A Task's Secrets And Files Found By Its Identity

```json
{
  "$schema": "issue-1.json",
  "id": "B599",
  "title": "A Task's Secrets And Files Found By Its Identity",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 4,
  "blockedBy": ["B596", "B598"],
  "covers": "Gate, vault and file paths are reached through a task's identity."
}
```

**Why this size.** the gate, vault and file consumers routed through the identity.

Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Gate, vault and filesystem consumers (`TaskSecrets`, `GateSupport`, `GateServeCommand`, `ReviewText`, `HandIns`, `ApprovalBranch`, `TaskBase`, `TaskControl`, certification and resume) find a task's secrets and files through its identity, never by rebuilding a name: an old task signs and pushes with its own key and token, and removing or pruning one task never touches another's.

## Plan

[B538](README.md#b538)'s plan, slice 3: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- An old task's gate works with its own secrets after the change; a sibling's secrets are never read or removed.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
