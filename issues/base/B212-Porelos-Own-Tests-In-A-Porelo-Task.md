# B212 — Porelo's Own Tests In A Porelo Task

**Status:** Implementing. Built: the five groups, and two more the measurement found. Item 4 was the test's own
context, which set no `XDG_RUNTIME_DIR`. `Podman.execute` starts `podman exec` itself, so a stand-in podman is
named through `porelo.podman` as the gate is through `porelo.binary`. `vault unlock` now refuses a wrong passphrase
source (64) before a refused keyring (69), as `vault init` did, and the tilde test reads `$HOME` as the code does.
**Measured on 2026-10-19 in the draft image as a task runs it: `clean verify` green, 2639 tests, 0 failed, 77
skipped by name** (it was 92 red). Left: the whole check and the VM on the commit.
**Size:** M - five groups of tests in two modules, one test seam in `PoreloBinary` with the launcher Surefire names,
and one path that reads the real runtime directory; no behaviour a person sees changes.

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

## Plan

Read on 2026-10-19 in the tests and the in-image run's log (`runs/in-image/porelo/run.log`), one cause per group:

1. **`podman ps` where only its answer is meant, 11 tests.** `BackupRestoreTest` (8), `CheckoutApprovalTest`,
   `ClearingTest` and `DefaultProjectTest` build their context on a real `ProcessCommandRunner`. The command under
   test asks `podman ps --all` which tasks exist, and in the image that fails with "Cannot run program podman". On a
   developer's machine it answers with whatever containers that machine has, so the tests also depend on the
   developer's own tasks today. Each takes a runner that answers `podman ps` with no containers and passes every
   other command (git) to the real one, as `CheckoutApprovalTest` already does for one case at its line 248. One
   such runner in the test sources, used by all four.
2. **The gate started as whatever `porelo` is on the `PATH`, 14 tests in `TaskRunCommandTest`.** `PoreloBinary`
   takes the running program when it is `porelo`, else the `porelo` beside it, else the bare name. In a test the
   JVM runs, so the gate is the developer's **installed** `porelo`, not the build under test, and in a task there
   is none. A test seam: `PoreloBinary` takes the system property `porelo.binary` first, when set. Surefire sets it,
   for `apps/app` only, to a launcher the build writes under `target/`: one line,
   `exec java -cp <the test class path> org.fuin.porelo.app.PoreloCli "$@"`. So the tests run the build's own gate, in
   a task and on a developer's machine alike. The property is read nowhere else, and the shipped binary never
   sees it set.
3. **`ProcessHardeningTest.noNewPrivilegesIsReadableWithoutBeingSet`** asserts the bit is off, which a task has set
   on purpose. What it means is that reading changes nothing: it reads twice and asserts both answers are the same,
   and that they agree with `NoNewPrivs` in `/proc/self/status`. That holds inside a task and on a machine; the bit
   is inherited, so no child could show it off inside a task.
4. **`ReconcileTest.what_a_person_held_survives_a_reconciliation`** meets `AccessDenied /run/user`, though its
   context is built on the test's own directory: some path in what it calls takes the runtime directory from the
   real environment rather than from the context. That path takes the context's, and the test then needs no
   change. Which path it is is read first, while building.
5. **`DaemonHardeningTest.aDaemonWhoseEntriesBelongToAnotherUserIsNotDumpable`** reads pid 1's `/proc` entries as
   belonging to another user. In a user namespace that maps one id, they are the test's own. It skips by a named
   assumption, "pid 1's /proc entry belongs to another user", read from the entry's owner, and runs unchanged on a
   machine.
6. **The keyring's 58** are [B211](README.md#b211)'s and skip once it is in.

## Acceptance

- The measurement repeated: `./mvnw clean verify` on a clone inside the draft image as a task runs gives no
  failure and no error, with each skip named by its assumption.
- On a developer's machine and the VM, the same tests run as before, and `TaskRunCommandTest` starts the build's
  gate, not the installed one: seen in a test that the started gate names the build's version.
- Every changed test seen red first in the image.

## To be checked

- **The launcher's start-up time:** a gate started as `java` takes longer than the native binary; whether any of
  `TaskRunCommandTest`'s waits needs more time for it.
- **Which path reads `/run/user`** in `ReconcileTest`'s case (item 4).
