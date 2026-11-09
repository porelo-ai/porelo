# [B548](README.md#b548) — A Repository's Arrivals Read As A Published File

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** M.
**Blocked by:** [B547](README.md#b547).

**What must be true.** Root publishes each repository's judged pushes as a file readable by the project's unlocked members, as the central
state; porelod reads it under the same file checks as [B543](README.md#b543) (regular, root's, not writable by group or others, missing
= no answer). The contract's arrivals reading changes accordingly.

## Why

Step 5, arrivals. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
