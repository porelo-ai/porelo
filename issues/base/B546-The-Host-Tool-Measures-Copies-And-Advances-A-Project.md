# [B546](README.md#b546) — The Host Tool Measures, Copies And Advances A Project

**Status:** Backlog. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
owned by Agent Machines; built after move 4, all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** L.
**Blocked by:** [B545](README.md#b545).

**What must be true.** Measuring against central (the central-state record [B543](README.md#b543) reads), copying and advancing `main`, and `check-central`
for `down`, as `porelo-host-project` and `porelo-host-push --check` do.

## Why

Step 4, second half. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is owned by Agent Machines; reviews of porelo's shared libraries stay Agent Core's.
