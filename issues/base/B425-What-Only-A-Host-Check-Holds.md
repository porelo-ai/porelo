# B425 — What Only A Host Check Holds

```json
{
  "$schema": "issue-1.json",
  "id": "B425",
  "title": "What Only A Host Check Holds",
  "state": "Accepted",
  "size": "S",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "A unit test holds what only a host check held: `porelod`'s host forge rule and the gate's refusal of another upstream; a removal with the vault shut says the task's forge token stays until the host's sweep."
}
```

**Why this size.** two unit tests on what `main` and the gate read from the host, and one line in a removal's answer.

**Severity.** low: both were seen red and green on a test host, and nothing in the build would notice if they came undone.

**What must be true.** The build fails if a host's rules stop reaching `porelod` or the gate.

**What holds today.**

- `PoreloDaemon.main` calls `HostForgeAddress.install()`, so `porelod` reaches a row with a `central` at the host's forge. A unit test does not run `main`.
- `GateSupport.gate` refuses an `--upstream` other than the host's forge. It reads `/etc/porelo-host/forge.conf` itself, which a unit test cannot place.
- The acceptance suite's machine has no host forge, so it passes through neither.

**To be checked.**

- `GateSupport.gate` already takes the file from its caller (an overload with `forgeConf`), so a unit test can hold its
  refusal. No test calls it with an upstream other than the host's forge yet.
- Whether a stand-in `forge.conf` belongs in the suite, which would hold both. It would be a machine that is a host in that one way only.

**Also found.** On a host, removing a task while the vault is shut does not run `task --end`, and says nothing of it: `TaskControl` asks only when the task's key can be read from the vault. Seen on a test host: the task's forge token stayed after the removal. The host's sweep drops it when the task's certificate has ended. The removal should say so.
