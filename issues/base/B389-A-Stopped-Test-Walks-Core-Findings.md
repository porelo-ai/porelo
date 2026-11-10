# B389 — A Stopped Test Walk's Core Findings

```json
{
  "$schema": "issue-1.json",
  "id": "B389",
  "title": "A Stopped Test Walk's Core Findings",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 113,
  "blockedBy": [],
  "covers": "Directories without group write, no Unsafe warning, and setup on a host vault said in words"
}
```

**Why this size.** proposed: three small fixes the stopped test walk of 2026-11-01 asked for before it resumes.

**Severity.** medium.

**What must be true.** An account set up by the host's helpers, with an umask of 002 and a host vault, is set up
without a refusal, a warning or a message that reads like a failure.

## Acceptance

- Porelo makes no directory writable by the group or others, whatever the umask; one that was there keeps its mode.
- porelod starts and answers its own account without a sun.misc.Unsafe warning, in the native image too.
- `porelo setup` without a terminal on a vault with no passphrase slot says what opens it and refuses nothing.

## Done

- `wire.Directories.create` makes each missing directory and takes the group's and others' write from it; not every
  directory maker uses it: `OrgAccess.settle` and `ProjectFile.remember` call `Files.createDirectories`
  without removing group write, so the directory acceptance line remains unmet. `GitTreeStore.keep` sets its
  directory to `0700` after creation. A person's state home uses `Directories.create`
  (`DirectoriesTest`, red under umask 002 without the change).
  Natively on the Ubuntu VM, with umask 002 and a fresh home: 5 group-writable directories with 900, none with 955.
- `SocketPeer.read` asks `ExtendedSocketOptions.SO_PEERCRED` and compares the caller with the socket's owner; the
  pid, read by nothing, is no longer answered (`SocketPeerTest`). Natively on the VM: porelod 955 answers `doctor`
  and `agents` of its own account, and its journal holds no Unsafe line. A caller of another account was not tried
  natively.
- `SetupCommand` says what opens a vault without a passphrase slot and does not try to open it
  (`SetupCommandTest`, red first); `vault login` and the passphrase commands still name `porelo host unlock`.
- Built during the stopped walk with unit tests only; the reading follows after the walk.
