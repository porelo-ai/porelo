# B394 — An Online Task On A Host With No Forge Passes On To Its Upstream

**Status:** Idea.
**Size:** S - one condition and its tests.

**What must be true.** No function of Porelo relies on a forge being on the host. An `online` task on a host with no
forge passes its branch on to the project's `upstream` at once, as on a machine that is no host.

## What fails today

Read in the code, not run. `TaskLaunch.certifiedGate` hands the gate the host's repository whenever
`porelo-host-person` exists (`new HostPerson(context).present()`), and `GateWiring` then adds `--host-push`. On a host
with no forge `porelo-host-person task` answers an empty token (`HostPerson.start`), so `HostPush` refuses the push with
"no forge token is kept for this task; the vault may be locked", which names the wrong cause.

## Acceptance

- Red first: an `online` task certified on a host whose `porelo-host-person task` answers no token gets no
  `--host-push`, and its gate passes on to `upstream`.
- With a token answered, it gets `--host-push` as today.
- A resumed task's gate passes on where it did.

## The credential

No new kind and no new rule: the pass-on takes the way an `online` gate with no `--host-push` takes today,
`FollowCredential` for the upstream's address through `VaultGitCredentials`. A shut vault lends nothing and the push is
refused, as today.
