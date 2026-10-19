# B212 — Porelo's Own Tests In A Porelo Task

**Status:** Accepted.

**What must be true.** porelo's whole local check is green inside a Porelo task: rootless, as the task's own user,
with the default seccomp profile, `no-new-privileges` set, and no podman, `porelo` or `/run/user` of the host. A test
that needs one of those skips where it is missing, or brings its own; none passes only because the developer's machine
has it.

## Why

Measured on 2026-10-19: `./mvnw clean verify` on a clone of porelo inside the image of its draft `porelo.yml`, run as
a task runs (`--userns=keep-id`, `no-new-privileges`, default seccomp, `LANG=C.UTF-8`), gave 92 of 2626 tests red.
58 of them are the kernel keyring, which [B211](README.md#b211) covers. The rest, by cause:
- **`podman` is not there, 11 tests:** `BackupRestoreTest` (8), `CheckoutApprovalTest`, `ClearingTest` and
  `DefaultProjectTest` run the real `podman ps` through `ProcessCommandRunner`.
- **The real `porelo` is not on the `PATH`, 14 tests in `TaskRunCommandTest`:** the git gate is started as the
  installed `porelo`, found on a developer's machine in `~/.local/bin`; in the task, "Cannot run program porelo".
- **`no-new-privileges` is already set, 1 test:** `ProcessHardeningTest.noNewPrivilegesIsReadableWithoutBeingSet`
  asserts the bit is off, and Porelo's own task sets it. The test is to prove that reading changes nothing.
- **No `/run/user`, 1 test:** `ReconcileTest.what_a_person_held_survives_a_reconciliation` reaches `/run/user`
  instead of a directory of its own.
- **One uid for every process, 1 test:** `DaemonHardeningTest.aDaemonWhoseEntriesBelongToAnotherUserIsNotDumpable`
  takes `/proc` entries to belong to another user, which a user namespace mapping one id does not give.

So an agent working on porelo in a Porelo task cannot get a green local build, which the project's plan needs.

## The shape proposed

- Each test above takes a fake where it only means the command's answer, or skips by a named assumption where it
  needs the real program, device or directory: never a test that passes only on a developer's machine.
- The measurement above, run again, is the acceptance: the module green, with the skips named.

## To be checked

- **Whether the git gate's tests should start the gate from the build's own classes** instead of the installed
  `porelo`, which would keep them running in a task rather than skipping.
