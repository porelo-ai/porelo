# B545 — The Host Tool Adds, Removes, Migrates And Takes Off A Project

```json
{
  "$schema": "issue-1.json",
  "id": "B545",
  "title": "The Host Tool Adds, Removes, Migrates And Takes Off A Project",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 40,
  "blockedBy": ["B541"],
  "covers": "`porelo-host project add"
}
```

Review since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.

**What must be true.** The forge-side work of a project (its organization and publisher, migration from central, taking it off) as
`porelo-host project` verbs with the Python's 116 tests ported harm by harm; Forgejo's admin calls are new Java beside
`HttpForgeDirectory`.

## Why

Step 4, first half. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief; merged onto [B541](README.md#b541) and reviewed by Agent Core.
- **The verbs.** `porelo-host project add <project> <admin> --signed-by <key>`, `remove`, `migrate`, `take-off` and `central-state`.
  - Same arguments, exit codes, files and forge calls as the Python `porelo-host-project`, in the same order.
  - The same trailing `--lock-fd`, and the same `flock` on `forge.conf` (`ProjectLock`).
  - Rollback on a failed or interrupted registration (`ProjectCancellation`).
- **Forgejo's admin calls** are in `HttpForgeAdmin`, beside `HttpForgeDirectory` in `organization/git-tree`: bounded, no redirect followed, an error body withheld. `forgejo admin` runs through `HostPrograms` as the forge account, never through a shell.
- **`central-state`** writes the Python's format byte for byte: field order, separators, ASCII escaping.
- **Root's files** go through `ProjectFiles`:
  - root's, not writable by group or others, private files `0600`, no symlinks;
  - written to a temporary file in the same directory, with owner and mode set before `fsync` and rename.
- **`HostPrograms`** now reads a child's stderr apart and bounded, and never mixes it into the answer. It gives a child either the fixed environment or exactly the one a verb names.
- **Tests.**
  - Of the Python's 116, 74 are paired one to one (`// py:` above each). The other 42 belong to [B546](README.md#b546) (give back, copy, advance and the pool keys), and are listed for it.
  - `organization/git-tree` (660), `host` (340) and `wire` (145) ran whole outside a sandbox, socket tests included.
  - Agent Core saw these go red with their checks broken: a locked admin, the organization naming the project for the admin, a group-writable `forge.conf` (which needed a new test, `aForgeConfigurationOthersMayWriteIsRefusedBeforeTheForge`), `migrate`'s missing directory, and port 0.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - every file and directory the verbs make gets its mode set explicitly, so a caller's umask (0000, 0002, 0777; tested in a child JVM) can neither open nor wedge a project;
  - `migrate` refuses a missing `organization/read` with 69 "run up first", as the Python does, instead of making it;
  - an interrupted `add` still sends both rollback DELETEs, and the interrupt is set again afterwards;
  - `run-key` is decoded strictly: invalid UTF-8 is refused with 69 and the registration rolled back;
  - `--` is refused with 64; port 0 in a central address with 65;
  - an over-limit read and a failed read-key deletion answer the Python's words and 69;
  - the forge's `Authorization` is built and sent as bytes and cleared. Where an API takes only a String (the JSON bodies, `HttpRequest.header`), the String lives no longer than the call.
  - Four paired tests that stayed green under a broken implementation now go through the command and the forge's real Unix socket.
- **Where it differs from the Python:**
  - A non-root caller gets the Python's 69 before parsing ([B546](README.md#b546) restored it; the frame's other verbs answer 77).
  - Byte limits and stricter UTF-8 and URI checks can refuse what the Python took.
  - An account is looked up through a checked `getent`.
  - A raw SIGINT may exit 130 where the Python gave 64. The rollback itself is tested.
- **Like the Python,** `project` is not refused through sudo: it is root's units' and setup's, and no sudo rule names it.
- **Left for later:** `ProjectLock` and [B541](README.md#b541)'s `HostConfigurationLock` are two `flock`s over the same three downcalls. Merge them when [B546](README.md#b546) adds a third user.
- **Not verified here:** a real Forgejo, `chown` as root, the native image's run, and the VM.

