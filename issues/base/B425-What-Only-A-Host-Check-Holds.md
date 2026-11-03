# B425 — What Only A Host Check Holds

**Status:** Idea.
**Severity:** low: both were seen red and green on a test host, and nothing in the build would notice if they came undone.

**What must be true.** The build fails if a host's rules stop reaching `porelod` or the gate.

**What holds today.**

- `PoreloDaemon.main` calls `HostForgeAddress.install()`, so `porelod` reaches a row with a `central` at the host's forge. A unit test does not run `main`.
- `GateSupport.gate` refuses an `--upstream` other than the host's forge. It reads `/etc/porelo-host/forge.conf` itself, which a unit test cannot place.
- The acceptance suite's machine has no host forge, so it passes through neither.

**To be checked.**

- Whether `GateSupport.gate` can take the place of that file from its caller, as `HostForgeAddress`'s own tests do.
- Whether a stand-in `forge.conf` belongs in the suite, which would hold both. It would be a machine that is a host in that one way only.

**Also found.** On a host, removing a task while the vault is shut does not run `task --end`, and says nothing of it: `TaskControl` asks only when the task's key can be read from the vault. Seen on a test host: the task's forge token stayed after the removal. The host's sweep drops it when the task's certificate has ended. The removal should say so.
