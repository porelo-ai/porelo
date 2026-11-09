# [B542](README.md#b542) — The Host Tool Sweeps Ended Task Tokens

**Status:** Review, since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.
**Size:** S.
**Blocked by:** [B541](README.md#b541).

**What must be true.** `porelo-host task sweep` deletes every task token whose certificate has ended, as `porelo-host-person sweep`; refused
through sudo.

## Why

Step 2. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief; merged onto [B545](README.md#b545) and reviewed by Agent Core.
- **The verb.** `porelo-host task sweep`, as `porelo-host-person sweep`:
  - first it retries every pending person lock (`HostRevocations`), keeping the intent until each owed step succeeds;
  - then it deletes, per person, the forge tokens of tasks whose recorded certificate has ended, and goes on past a person whose deletion fails;
  - it answers `{"swept": [...]}` sorted, or 69 naming what it deleted and what failed;
  - refused with 65 through sudo (`SUDO_USER` set): it is root's timer's alone.
- **One class per job, shared with [B545](README.md#b545).** The sweep's own Forgejo client, account lookup and command runner were dropped for [B545](README.md#b545)'s:
  - `HttpForgeAdmin` gained token paging and deletion by id;
  - `ProjectRuntime` gained the person's forge session, the temporary admin's cleanup and one strict `getent` parser;
  - `HostFiles` stages through `ProjectFiles`.
  - `wire`'s `SystemProgram` tells an absent optional program from an unsafe one.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - a page of tokens repeating an id is refused, never a guess at which token to delete;
  - a token name that is not a string refuses that one person, and the sweep goes on to the next; the temporary admin's client is closed either way;
  - a pending person who resolves to uid 0 is refused before any revocation touches root's resources;
  - `runuser`, `podman` and the like are searched on the sweep's fixed `PATH` and checked, also when the forge command is built;
  - the `project` verbs keep their own diagnostics: a missing program and an unreadable `getent` stay I/O failures, as before the merge.
  - Refuted, as the Python does the same: a failed cleanup of the temporary admin answering over the sweep's own errors, and the lock's extent.
- **Tests.**
  - `host` (390), `organization/git-tree` (693) and `wire` (146) ran whole outside a sandbox, socket tests included.
  - The test pairing the retry of pending locks now checks each revocation command and the delivery's removal, and the sweep's test checks the forge socket, the credential and the client's closing.
  - Agent Core saw these go red with their checks broken: the refusal through sudo, the end-time test, going on past one person's failure, the repeated id within a page, and the refusal of uid 0.
- **Where it differs from the Python:** [B545](README.md#b545)'s bounds apply to the sweep too: 1 MiB per answer, 30 seconds per request, 60 seconds per maintenance command. The Python's were unbounded or per socket operation.
- **Not verified here:** a real Forgejo, root, the native image's run, and the VM.
