# [B543](README.md#b543) — The Person's Host Commands In Porelo

**Status:** Implementing. Filed 2026-11-09 from the porelo-host CLI proposal the operator took (a module `host/` of porelo,
built by Agent Core and read by Agent Machines; built all at once: every issue of it on one branch, one hand-over, its real test
move 5). Priority to be set by the operator; nothing built before the operator's word after move 4.
**Size:** L.
**Blocked by:** [B540](README.md#b540).

**What must be true.** porelo gains `porelo host forge-token` (calls `sudo -n porelo-host forge issue-token`, the token read from its
stdout and stored into the vault; the passphrase asked only when the vault is shut), `porelo host central-state
<project> [--json]` and `porelo host stop-secrets [--stdin]` (at up's stop root starts it as the person with
`runuser`). porelod reads the follow's central-state file directly ([B531](README.md#b531)'s `HostCentral` program call dropped), and
trusts it only as a regular file, root's, not writable by group or others; missing or refused is `cannot-say`, never
up to date. [B539](README.md#b539)'s `storeCommand` names `porelo host forge-token`.

## Why

Step 3 without push and pull ([B544](README.md#b544)). The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.
