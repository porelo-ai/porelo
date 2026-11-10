# B546 — The Host Tool Measures, Copies And Advances A Project

```json
{
  "$schema": "issue-1.json",
  "id": "B546",
  "title": "The Host Tool Measures, Copies And Advances A Project",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 37,
  "blockedBy": ["B545"],
  "covers": "`porelo-host project measure-central | copy | advance | check-central`."
}
```

Review since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.

**What must be true.** Measuring against central (the central-state record [B543](README.md#b543) reads), copying and advancing `main`, and `check-central`
for `down`, as `porelo-host-project` and `porelo-host-push --check` do.
`project pool-key send|withdraw` distributes or withdraws the same `PORELO_POOL_SSH` secret as project registration, preserving the Python helper's arguments, replies, locking and retry behavior.

## Why

Step 4, second half. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, on [B545](README.md#b545) and [B542](README.md#b542); reviewed by Agent Core.
- **The verbs** of `porelo-host project`: `give-back-check`, `project-copy`, `project-advance`, `measure-central`, `check-central` (for `porelo-host-push --check`; `remove` calls it) and `pool-key send|withdraw`, answering as the Python `porelo-host-project` and `porelo-host-push` do. No twin of [B545](README.md#b545)'s classes: `ProjectRuntime`, `ProjectFiles`, `ProjectReadKeys` and `HostPrograms` were extended.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - `project-advance` writes every file private inside the container (a fixed umask), and refuses with 65, leaving `main` alone, when a project file's blob is missing (the Python compared two empty answers);
  - a CA reached through a link or a writable parent is refused, and an old copy that cannot be cleaned no longer stops the next question, as in the Python;
  - commit inventories stream past 1 MiB (30 000 hashes are compared);
  - `check-central` puts no deadline of its own on a fetch or clone (the Python has none; a slow central is not a refusal), and `remove` keeps the Python's 600-second outer deadline; the measurement has one budget of 120 seconds across all projects, as the Python's follow;
  - `porelo-host-push`'s prefix and newlines are kept, and non-root `project` verbs answer the Python's 69 before parsing (`check-central` keeps its own 77).
  - Refuted with reasons: a parent link the parent check already refuses, CR kept before LF as the Python does, Strings holding secrets where the Python holds them too.
- **Tests.** All 116 Python `project` tests are now paired across [B545](README.md#b545) and this issue, plus 4 of `porelo-host-push --check`. `host` (524), `organization/git-tree` (712) and `wire` (146) ran whole outside a sandbox.
- **Left to [B550](README.md#b550):** choosing migrated projects, pruning stale ones and the follow's whole deadline.
- **Not verified here:** real root, `chown` and ACLs across accounts, a real Forgejo, Podman and the VM.
