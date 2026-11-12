# B384 — A Start That Waits For Its Helpers

```json
{
  "$schema": "issue-1.json",
  "id": "B384",
  "title": "A Start That Waits For Its Helpers",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 43,
  "blockedBy": [],
  "covers": "A start and a resume wait for their helpers and fail with 70 when one does not come up, a host's refusals come before the resume record, and a racing start stops only its own task",
  "openQuestions": 0
}
```

**Why this size.** M, measured 2026-11-12: three modules (app-task, app-gate, app-vault) and the stand-ins across the
task tests come first. Proposed before: the gate and relay awaited until they record their pid, a helper that does not come up failing
the start and the resume with a code, the host's two refusals moved before the resume record, a failed start's
cleanup bound to the id it recorded itself, and the tests' helpers turned into stand-ins first.

**Severity.** medium.

**What must be true.** `porelo task start` and a resume answer success only when every helper of the task runs, and a
start that fails leaves nothing it made running and stops nothing it did not make:

- The gate and the relay are awaited until each has recorded its pid, and `vault serve` records its pid before its
  token, so a start that fails in that window leaves no helper running in its scope.
- A start and a resume whose helper (`vault`, `relay`, `gate`, `watcher`) does not come up fail with a code (70),
  never 0.
- On a host, the kept signing key and the person's forge token are refused before the resume record is saved, so a
  second `task start` of the refused task refuses again rather than resuming it without either.
- When two starts of the same task name race, the one whose create fails on the taken name stops nothing of the
  other: a failed start's cleanup trusts only the container id it recorded itself, never one another start wrote for
  the name.

## Plan

- Every gated start in the unit tests runs this build's own `gate serve` against the account's own directories, and a
  resume's tests record helpers whose commands never write a pid file; awaiting the helpers would turn those into
  failures or 20-second waits. So the helpers those tests start become stand-ins first, across the test classes, and
  only then is the wait added.
- The host's two refusals happen only on a host, whose helper is at a fixed root-owned path, and in a certified start;
  a seam for the helper's path lets a unit test reach them.

Read in the code on 2026-11-12:
- `GateWiring.startGate` (`:198`) records the gate helper. Its only wait (`:358`, 20 s) is on the gate's answer, not
  its pid. `GateServeCommand.writePidFile` writes the pid only after the server has started (`:319`).
- `CredentialWiring.startRelay` (`:98`) records the relay with no wait at all. `awaitToken` (`:665`) waits on the
  vault's token, and `VaultServeCommand` writes its pid (`:468`) after that token.
- `TaskControl`'s resume starts the same helpers again.

Steps:
1. Stand-ins: a `HelperStandIn` in the test sources writes its pid file and blocks. The gated starts in
   `TaskRunCommandTest`, `TaskLifecycleCommandsTest` and `StartCheckTest` use it in place of this build's `gate serve`.
2. Red: with a stand-in that never writes its pid, the start answers 70 and leaves nothing running in the scope.
3. One wait, `HelperPid.await(pidFile, deadline)`, in `startGate` and `startRelay`. `VaultServeCommand` writes its pid
   before its token. A missing helper fails the start or the resume with 70 and unwinds what this start made.
4. The host's two refusals move before `TaskState`'s resume record. A seam for the helper's root-owned path lets a unit
   test reach them.
5. A failed start's cleanup reads only the container id it recorded itself, held in `TaskLaunch`, and never the
   name's record on disk.

## Acceptance

- A test whose gate or relay never records its pid sees the start answer 70 and no helper left running.
- A test whose helper fails on resume sees the resume answer 70.
- A test of a host start whose signing key is refused sees a second start refuse, not resume.
- A test of two starts of one name sees the first start's task still running after the second fails.
- Each seen to fail first.

## Open questions

- None: the behaviour is settled in *What must be true*.

## To be checked

- On the Ubuntu VM, a resumed task attached in the foreground and ended by `SIGTERM` (from `timeout`) was left running
  with its gate and watcher dead and their pid files still there. Whether a signal to an attached start does this on
  every build, and whether it belongs here, is not measured.
