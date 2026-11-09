# [B549](README.md#b549) — The Push Judge In The Host Tool

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** L.
**Blocked by:** [B547](README.md#b547).

**What must be true.** `porelo-host judge-push`, called by the socket's service from Forgejo's pre-receive hook, judges signatures, task
branches and the pusher's own tasks as `verify-push` does, using porelo's `HostArrivals`/`CommitVerifyCommand` code as
a library where the shapes allow.

## Why

Step 6. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
