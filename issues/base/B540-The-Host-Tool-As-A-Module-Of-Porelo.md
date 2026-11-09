# [B540](README.md#b540) — The Host Tool As A Module Of Porelo

**Status:** Implementing, since 2026-11-09, on the operator's order to build the host's own program now; nothing of it
reaches `main` before the operator's move-4 test. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a
module `host/` of porelo, built by Agent Core and read by Agent Machines; built all at once: every issue of it on one
branch, one hand-over, its real test move 5).
**Size:** M.
**Blocked by:** -.

**What must be true.** A module `host/` of porelo, built by Agent Core and read by Agent Machines, builds the native binary `porelo-host` in porelo's reactor and
the package `porelo-host` (`.deb`, binary at `/usr/sbin/porelo-host` and its man page), signed and published as
porelo's packages; `porelo-host --version` answers porelo's version. The boundary every later verb uses is here:
running as root it reads no configuration from the environment and refuses a caller's environment beyond `LANG` and
`TERM`; the native image takes no JVM options from the environment (a test sets `JAVA_TOOL_OPTIONS` and
`_JAVA_OPTIONS` and sees no effect); every program through `SystemProgram`; a new root-owned-file check in `wire`
beside it; a verb acting for a person takes it from `SUDO_USER` alone and refuses without it.

## Why

Step 0 of the plan; package-check grows an install line for `porelo-host`. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
