# [B540](README.md#b540) — The Host Tool As A Module Of Porelo

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
owned by Agent Machines; built after move 4, all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** M.
**Blocked by:** -.

**What must be true.** A module `host/` of porelo, owned by Agent Machines, builds the native binary `porelo-host` in porelo's reactor and
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
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is owned by Agent Machines; reviews of porelo's shared libraries stay Agent Core's.
