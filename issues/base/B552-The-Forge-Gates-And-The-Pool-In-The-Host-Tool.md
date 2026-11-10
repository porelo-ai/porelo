# B552 — The Forge Gates And The Pool In The Host Tool

```json
{
  "$schema": "issue-1.json",
  "id": "B552",
  "title": "The Forge Gates And The Pool In The Host Tool",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 33,
  "blockedBy": ["B540"],
  "covers": "`porelo-host gate forge --web|--git|--runner` after porelo-host's load measure; `pool revert | make-vm | find-vm | delete-vm`."
}
```

Review since 2026-11-09. Built on the operator's order of 2026-11-09; nothing of it reaches `main` before
the operator's move-4 test.

**What must be true.** The forge's three socket-activated gates, measured under move 4's load before they replace the Python (the web gate's keep-alive, porelo-host's own measure); the
pool's revert service and its VMs made in Java (`virsh`/`virt-install` through `SystemProgram`), `porelo-vm` staying a
build tool in porelo-host.

## Why

Step 8. The proposal (the operator's rule: what cannot run in user mode and is not done by a person is `porelo-host`'s;
the person it acts for is `SUDO_USER`, never an argument) places every host script's verb; porelo-host's collection
change is Machines' own, in porelo-host: the collection installs the `porelo-host` package, its units, timers,
sockets and sudo rules call it, and every host script goes. The contract between the two: porelo publishes the
`porelo-host` package with every verb of these issues, at `/usr/sbin/porelo-host`, before the collection pins it. `host/` is built by Agent Core and read by Agent Machines, the domain owner of the host scripts it replaces.

## As built

Built by Codex CLI on Agent Core's brief, on [B542](README.md#b542); reviewed by Agent Core.
- **The verbs:** `porelo-host forge gate` (the forge's web, git and runner gates, socket-activated), `forge measure`, and `pool revert|give-back|make|address|delete`, answering as `porelo-forge-gate`, `porelo-pool-revert`, `porelo-pool-give-back` and `porelo-vm` do. `address` and `delete` were added after the reading: the collection's `vm.yml` calls them.
- **After a reading by another model,** fixed, each with a test seen failing first:
  - the web gate resolves the peer once per connection, not per keep-alive request;
  - an unknown or numeric peer gets 403, as in the Python, instead of ending the gate;
  - every header a `Connection` header names is stripped (the Python strips only the first);
  - a virsh failure in revert still reaches `destroy`;
  - a failed ISO build removes its partial `seed.iso`, which holds credentials;
  - pool and measure keep the shell scripts' messages and exit 1;
  - `forge measure` opens only the connections it uses.
  - Refuted with reasons: 102/103 responses (Python's client skips only 100, as here) and `--one` widening names (the Bash never checks the list).
- `pool delete` refuses an unreadable census, where `porelo-vm` hides a failed read.
- **Tests.** The weak tests now enter as the program does: SO_PEERCRED on an inherited descriptor, invalid bytes from the file. `host` (572), `organization/git-tree` (694) and `wire` (146) ran whole outside a sandbox.
- **Not verified here:** the gates under real load, systemd socket activation, libvirt and the VM.
