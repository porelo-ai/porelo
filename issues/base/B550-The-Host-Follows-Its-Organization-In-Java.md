# [B550](README.md#b550) — The Host Follows Its Organization In Java

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
owned by Agent Machines; built after move 4, all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** L.
**Blocked by:** [B546](README.md#b546), [B547](README.md#b547).

**What must be true.** The follow (timer), refresh (window over root's ssh; the admin's central token on stdin only for a waiting
migration) and renew-pin, as `porelo-host-person org ...`, calling [B545](README.md#b545) and [B546](README.md#b546) in-process instead of a subprocess.

## Why

Step 7, the follow; the biggest single piece (with [B551](README.md#b551) about 2800 lines of Python). The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is owned by Agent Machines; reviews of porelo's shared libraries stay Agent Core's.
