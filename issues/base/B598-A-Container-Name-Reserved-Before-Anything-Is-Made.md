# B598 — A Container Name Reserved Before Anything Is Made

```json
{
  "$schema": "issue-1.json",
  "id": "B598",
  "title": "A Container Name Reserved Before Anything Is Made",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 3,
  "blockedBy": ["B596"],
  "covers": "Starts reserve their container name first; a taken name is refused by its owner."
}
```

**Why this size.** the account-wide reservation every start shares, crash recovery and the typed refusal.

In Review since 2026-11-10 (`porelo-project` ST307). Filed 2026-11-10 in `porelo-project` ST307 from [B538](README.md#b538)'s approved plan (the operator, 2026-11-10).

High priority (the operator, 2026-11-09, for [B538](README.md#b538)).

**What must be true.** Before any helper, mailbox, policy, key or token is made, a start reserves its container name under one account-wide lock shared by the CLI, the daemon and MCP; a name another task holds - old or new, known or not - is refused as `ContainerNameTaken` naming its owner where known, exit 64, before anything is written. A crashed start's reservation is recovered only by the same attempt. Creation still uses old names.

## Plan

[B538](README.md#b538)'s plan, slice 2 ("Legacy overlap and concurrent starts"): its builder's part, the files it names and the tests that go red first
are this issue's plan. Read [B538](README.md#b538)'s "Design to approve before building" first; the evidence lines there are where
to start.

## Acceptance

- Two concurrent starts of one name make one container; a name held by another project's task is refused before anything is made.
- The tests [B538](README.md#b538)'s slice names went red first and pass; no task of another project is touched.


## As built

`TaskReservations` in `app-base` publishes an owner-only claim in `state/task-identities` before
`TaskLaunch` makes task state. The CLI, Tasks1 Start and MCP children use that launch, and existing-task
resume takes the same reservation. `FileLocks` supplies the account-wide thread and `FileChannel.lock`
exclusion at `state/task-names.lock`. Starts release that lock after checking and claiming, before
building an image or starting helpers. Claims record the logical project and task, actual name,
attempt, stage, container id and holder process with its start instant. Initial publication is a
no-replace hard link of a complete private temporary file; later transitions replace atomically.
Files and the identity directory are forced before publication is reported complete.

A different owner, an unrecognized container, retained task or transport state, vault-only scopes,
a link or unreadable ownership cannot supply a free name. The shared refusal is `ContainerNameTaken`,
exit 64 on the CLI, with the requested owner and optional proven owner fields in Tasks1. A failed
start leaves its attempt recoverable. Only that attempt can continue, and reconciliation checks
both id copies against the live container and ownership. A container made before its id was
recorded is never adopted. Recovery after id recording completes the same claim's created stage.

Prune and both task and transport secret censuses keep committed unfinished attempts, even without
a container and beyond the old filesystem grace. Prune, project and account clearing, vault clearing,
stop and removal take the name lock before a vault update; a vault callback reads committed claims
without acquiring that lock. Existing-task resume takes its task lock after claiming the name.
An explicit removal can cancel a proven unfinished attempt without a container; normal removal
retires the claim while retaining its historical logical owner. A later task of that same logical
owner may start a new attempt, while another owner cannot inherit the name from absent runtime state.
Pruning an unknown orphan records unknown historical ownership before deleting its last files.
Removal can also finish when only a created claim remains, after confirming its id and actual name
are absent. A pending claim is checked before the daemon's credential preflight.

Unit tests went red before each part: concurrent callers making one reservation and one container with
untouched policy and mailbox, legacy overlap, publication failures, the id-recording crash windows,
conflicting ids, unknown state and links, immediate vault pruning, state and mail beyond the grace,
cancellation and the wire refusal. A separate-JVM test proves the file lock excludes another process; it
was seen red with the lock bypassed.

Taken together with the start code, two cases needed the reservation to recognize a task's own past:
resuming a stopped task whose retained state proves it is this project and task, and resuming a kept task
whose failed start left a claim whose holder has ended, which takes over that claim's original attempt.
Each went red first. Fixtures that wrote a task's state or listed its container without the recorded id
a real start leaves were given that id, or an earlier attempt of the same task.

Creation still uses legacy names. The remaining gate, vault and file consumers belong to
[B599](README.md#b599), messaging and daemon/MCP ownership to [B600](README.md#b600), and the new
allocator to [B601](README.md#b601).
