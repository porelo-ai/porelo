# [B544](README.md#b544) — The Gathered Push And Pull Take The Central Token From The Vault

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** M.
**Blocked by:** [B543](README.md#b543).

**What must be true.** `porelo host push` and `porelo host pull` do what `porelo-host-push` and `--pull` do, reading the central token from
the person's vault (the passphrase only when it is shut) instead of a hidden prompt on stdin; a missing entry is
refused naming the vault key and how to store it. The host keeps only `porelo-host project check-central` ([B546](README.md#b546)) for
`down`.

## Why

Steps 3 (push, pull) and 3a. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
