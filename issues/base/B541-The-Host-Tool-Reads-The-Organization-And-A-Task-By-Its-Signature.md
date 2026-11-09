# [B541](README.md#b541) — The Host Tool Reads The Organization And A Task By Its Signature

**Status:** Implementing, since 2026-11-09, on the operator's order to build the host's own program now; nothing of it
reaches `main` before the operator's move-4 test. Filed 2026-11-09 from the porelo-host CLI proposal the operator took.
**Size:** L.
**Blocked by:** [B540](README.md#b540).

**What must be true.** `porelo-host org status [--json]` (root's read of host, organization, persons, projects, repositories and their
central state) and `porelo-host task show <fingerprint>` (through `sudo -n`, `SUDO_USER`'s tasks only) answer as
`porelo-host-person org status` and `task --show` do today, compared on the rehearsal VM; the organization card's and
the records' readers move down from `apps/app-*` into their libraries where `host/` needs them.

## Why

Step 1; proves card and record reading before anything writes. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
