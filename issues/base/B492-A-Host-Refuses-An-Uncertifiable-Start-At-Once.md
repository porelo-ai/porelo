# [B492](README.md#b492) — A Host Refuses An Uncertifiable Start At Once

**Status:** Implementing; built on the operator's word that every finding of the trial move is fixed before the
second move.
**Size:** S - one check earlier in the start path and its tests; the refusal's words stay as they are.

**What must be true.** A task start on a host that has no way to have the task certified - no certificate asked of
the person's computer, and no key of a certifier in an agent on the host - is refused before anything is prepared for
it: before the vault is opened, the broker started or the image built. The refusal is the one the start gives today,
naming the line to run from the person's own computer with `--host`.

## Found

- **Seen during the trial move.** A start on the host was refused for want of a certificate only after the vault had
  been opened and the image prepared, minutes in.
- `TaskLaunch` asks `TaskCertifying.atStart` only after the vault is open and the image ready, and on a host
  (`HostPerson.present()`) throws `TaskCertification.Refused(TaskCertifying.noCertificate(...))` when nothing
  certified the task. Whether anything could certify it is known before any of that: a remote certifier
  (`remoteCertifier(project, host.present(), certifiers)`) or this machine's agent socket holding a certifier's key.

## Plan

- Before the start opens the vault, `TaskLaunch` asks the same question `atStart` would without certifying: on a
  host, with a workspace, is there a remote certifier, or an agent socket that holds one of the project's certifiers'
  keys? If neither, it throws the same `TaskCertification.Refused` with the same `noCertificate` words.
- The late refusal stays, since a certificate that comes back refused is still found only there.
- A machine that is no host, and a project whose certifiers are empty, start as today.
- **Red first:** a start on a host stand-in with no remote certifier and no agent key, whose test records the order of
  the start's steps, asserts that the refusal comes before the vault is opened and that nothing is left behind.

## Acceptance

- The test above fails on today's start, which opens the vault first, and passes after.
- A host start with a certifier's key in its agent, and a start from the person's computer with `--host`, still start
  in their existing tests.
