# [B547](README.md#b547) — Task Accounts And Lent Tokens Through The Host Tool

**Status:** Review, since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.
**Size:** L.
**Blocked by:** [B541](README.md#b541).

**What must be true.** `task start <task>` reads the task's certificate on stdin from porelod, refuses one not signed by `SUDO_USER`'s
current key for `task:<project>:<task>`, and answers the gate token on stdout; `task end`; `forge issue-token` and
`lend-token` answer on stdout, never on a command line. `HostPerson`, `HostForgeLending` and `HostForgeAccess` call
`/usr/sbin/porelo-host` alone and refuse plainly on a host without it, never falling back to the scripts: no host is
set up between porelo's push and the collection's.
sudoers for `%porelo-people` (Ansible): exactly these and `task *`.

## Why

Step 5. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, on [B541](README.md#b541), [B542](README.md#b542) and [B546](README.md#b546); reviewed by Agent Core.
- **The verbs:** `porelo-host task start|end` and `forge issue-token|lend-token`, as the Python helpers answer them. `HostTasks` stays the one task-record implementation; `HostCertificate`, `HostTaskCheck` and `HostTokens` are new. `HttpForgeAdmin` gained a predicate to its paged deletion; no second organization parser.
- **The callers** in `apps/` reach `/usr/sbin/porelo-host` through `sudo -n` with a checked program, and refuse with the package's name when it is missing. Their tests use a checked stand-in for the program, never a path from the environment.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - `/run/porelo-host` and its public parents are created traversable (0755), as the Python's, so people and sshd reach their sockets;
  - a machine that is no host never asks sudo for a task record, and a missing helper is a traced refusal, never an exception;
  - the KRL is read from the descriptor whose file was checked;
  - the checker's diagnostics are kept, its timeout keeps its kind, and it never echoes a certificate;
  - the person lookup has no invented deadline;
  - two stand-ins that invented accounts or a host are fixed.
  - Refuted with reasons: cross-person `task show` (the membership rule of [B541](README.md#b541) already serves a shared project) and the collision and retry handling.
- **Exit codes** are the Python's, by Agent Machines' word: 69 not root, 64 no caller, 65 an unknown or mismatched task or token. The frame's 77 and 78 stay for refusals the Python never had. The man page names each verb's codes.
- **PATH:** children run with `/usr/sbin:/usr/bin:/sbin:/bin`; nothing under `/usr/local` (the Python's helper had it).
- **Tests.** 29 of 30 Python task and token tests are paired; the sudoers test belongs to the collection. `host` (635), `organization/git-tree` (712), `wire` (146) and `apps/app` (3663) ran whole outside a sandbox, green.
- **Not verified here:** the VM, real Forgejo accounts, systemd credential delivery, root's `chown`, the native binary and the package.
