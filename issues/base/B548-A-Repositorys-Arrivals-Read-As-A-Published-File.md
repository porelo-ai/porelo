# B548 — A Repository's Arrivals Read As A Published File

```json
{
  "$schema": "issue-1.json",
  "id": "B548",
  "title": "A Repository's Arrivals Read As A Published File",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 24,
  "blockedBy": ["B547"],
  "covers": "root publishes arrivals with a read ACL; porelod reads the file, no sudo verb."
}
```

Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.

**What must be true.** Root publishes each repository's judged pushes as a file readable by the project's unlocked members, as the central
state; porelod reads it under the same file checks as [B543](README.md#b543) (regular, root's, not writable by group or others, missing
= no answer). The contract's arrivals reading changes accordingly.

## Why

Step 5, arrivals. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Root appends judged pushes under the private ledger's exclusive lock, fsyncs them, and atomically
publishes the complete snapshot with named-user read ACLs. Publication streams the ledger; follow
scans its locked descriptor with a 128 KiB byte bound per line instead of a total ledger size limit.
Only incoming migration commits are retained while scanning old history. Arming records migration
rows and its marker privately; follow publishes after admitting the current members.

Readers must be unlocked project members with unlocked, non-pending person files and existing local
accounts. Their cards must remain present, unrevoked and unexpired in both the applied organization
and person files, including the applied revocation list. The same policy publishes central state.
Follow closes arrivals and central access before fallible trust publication; failed reader replacement
removes the stale answer or leaves its directory closed. A refused push restores the exact previous
ledger, removes a newly empty file, and retries a descriptor detached by another writer's rollback.
Repository and project removal revoke public arrivals before deleting private history.

Tasks1 `Arrivals` reads the fixed published path directly with root ownership, file and ancestor type,
permission and no-symlink checks. It keeps a 16 MiB response bound and validates complete UTF-8 JSONL
rows, UTC arrival times, refs and exact signer provenance without rejudging signatures. Missing,
empty, unsafe, oversized or malformed files answer `cannot-say`; an empty armed repository has an
`armed` row. Repository display casing is folded to the forge's lowercase spelling. Arrivals is an
MCP reading method, and a task's endpoint restricts it to that task's project.
