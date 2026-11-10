# B599 — A Task's Secrets And Files Found By Its Identity

```json
{
  "$schema": "issue-1.json",
  "id": "B599",
  "title": "A Task's Secrets And Files Found By Its Identity",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 4,
  "blockedBy": ["B596", "B598"],
  "covers": "Gate, vault and file paths are reached through a task's identity."
}
```

**Why this size.** the gate, vault and file consumers routed through the identity.

In Review since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Gate, vault and filesystem consumers (`TaskSecrets`, `GateSupport`, `GateServeCommand`, `ReviewText`, `HandIns`, `ApprovalBranch`, `TaskBase`, `TaskControl`, certification and resume) find a task's secrets and files through its identity, never by rebuilding a name: an old task signs and pushes with its own key and token, and removing or pruning one task never touches another's.

## Plan

[B538](README.md#b538)'s plan, slice 3: its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- An old task's gate works with its own secrets after the change; a sibling's secrets are never read or removed.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.

## As built

Gate signing, host forge tokens, certificate renewal, resume and removal now use the actual container
and recorded id of the verified task. A gate captures its original identity and checks it again under
the name lock for each secret read, before taking the vault lock. Renewing a certificate checks the
same incarnation before storing the key or updating its end. Old container names, paths, provider
tokens and route tokens stay in their original namespaces. The first-slash vault-owner split is
unchanged.

The shared retained-history lookup supplies review instructions, hand-ins, approval and checkout
branches, review bases and repository selection. It reads the original records or retained ownership
after removal, refuses contradictory surviving records, and resolves rescued pushes without borrowing
the deliveries of a distinct task whose name ends in the rescue suffix. History lookup initializes no
identity metadata.
Task-secret pruning and explicit deletion require proven retirement or a verified owner; unknown
tombstones and vault-only leftovers cannot authorize deletion. The task-secret portion of explicit
pruning uses the same rule, while transport pruning is unchanged.

The planned six test classes have observed failing runs before their fixes. Additional regressions
cover rescued instructions, rescue-suffix isolation and a gate surviving an id replacement. The
required read-only review used another model and the seven local skills. Its three findings were
reproduced by six failing cases: contradictory surviving ids without a sidecar, unknown or
contradictory retired scopes on both deletion paths, and explicit pruning of vault-only scopes. Those
fixes pass with the final selected install: 218 tests, no failures, errors or skips. Further red runs
hold panic's single recorded-id inventory and cleanup of a canceled, known reservation that never
made a container. Unknown tombstones remain protected. The affected shutdown, authority and journal
fixtures now supply the ids, sidecars and coherent project labels a real start writes.

Integration on top of [B600](README.md#b600) found one fault in production code: every start, a
dry run included, looked the given name up through podman before reading it as a task name, so a dry
run touched the runtime. Only a name that reads as a container name (it starts with `porelo-`) is
looked up now; the seven dry-run tests that went red pass. The gate approval, build reader, mailbox
lifetime and daemon stop, panic and review fixtures now record the ids and sidecars a real start
writes. Whole runs of the touched modules: apps/app 4,415 tests and daemon 394 tests, no failures.

Creation still uses legacy names. Messaging, transport ownership, watches and daemon/MCP sockets stay
with [B600](README.md#b600); enabling compact names for creation stays with [B601](README.md#b601).
No container rename, secret migration, machine deployment, network download, commit or push was made.
