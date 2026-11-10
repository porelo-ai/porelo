# B598 — A Container Name Reserved Before Anything Is Made

```json
{
  "$schema": "issue-1.json",
  "id": "B598",
  "title": "A Container Name Reserved Before Anything Is Made",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 3,
  "blockedBy": ["B596"],
  "covers": "Starts reserve their container name first; a taken name is refused by its owner."
}
```

**Why this size.** the account-wide reservation every start shares, crash recovery and the typed refusal.

Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Before any helper, mailbox, policy, key or token is made, a start reserves its container name under one account-wide lock shared by the CLI, the daemon and MCP; a name another task holds - old or new, known or not - is refused as `ContainerNameTaken` naming its owner where known, exit 64, before anything is written. A crashed start's reservation is recovered only by the same attempt. Creation still uses old names.

## Plan

[B538](README.md#b538)'s plan, slice 2 ("Legacy overlap and concurrent starts"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- Two concurrent starts of one name make one container; a name held by another project's task is refused before anything is made.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.
