# B384 — A Start That Waits For Its Helpers

```json
{
  "$schema": "issue-1.json",
  "id": "B384",
  "title": "A Start That Waits For Its Helpers",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 45,
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

## Built (2026-11-13)

Line numbers checked against `916577457`: `GateWiring.startGate` is at `:254`, not `:198`, and the 20 s wait at
`:415` is the gate's reachability probe, not `:358`. `GateServeCommand.writePidFile` is defined at `:174` and called
after the server starts at `:342`, not `:319`. `CredentialWiring.startRelay` (`:98`), `awaitToken` (`:665`) and
`VaultServeCommand`'s pid write (`:468`, after the token) were where the plan said.

1. Stand-ins: `HelperStandIn` (test sources) is a shell helper that records itself as `HelperPid.record` would, with
   the same `pid start-millis` read from `/proc`, in its `--pid-file` and in a registry, then blocks; `stop` ends only
   the ones in its registry, by verified identity. `OwnPorelo`'s launcher answers `gate serve` with it and stops its
   stand-ins after each test. The gated starts in `TaskRunCommandTest` and the helpers recorded in
   `TaskLifecycleCommandsTest`'s and `ResumeVaultOfferTest`'s resumes use it. `StartCheckTest` has no gated start and
   needed none.
2. Red: `aStartWhoseGateNeverRecordsItsPidFailsAndLeavesNoHelperRunning`,
   `aRelayThatNeverRecordsItsPidIsNotUpAndIsNotLeftRunning`, `aResumeWhoseHelperNeverComesUpFailsAndStopsIt`.
3. `HelperPid.await(pidFile, deadline)` (wire). `startGate` and `startRelay` return whether the helper came up within
   `GateWiring.HELPER_PATIENCE` (20 s); one that did not is stopped, waited for until it has exited (5 s at most), and the start unwinds through its cleanup with 70.
   A resume awaits each helper it started against one deadline; one that did not come up is stopped and the resume
   answers 70 (`HELPERS_INCOMPLETE`). `VaultServeCommand.recordedThenPublished` records the pid before the token and
   publishes no token when it cannot. `CredentialWiring` got a `namespace` seam so a unit test reaches the relay.
4. The host block (kept signing key, forge token, certificate records) now runs before the resume record,
   `TaskState.save` and `TaskSecrets.keep`; a refusal there removes the container as refused before it was handed on,
   so the next start creates afresh and refuses again. No new seam was needed: `PoreloPaths` already carries the host
   program and directory, and `HostPerson(context, program)` takes the helper's path.
5. `TaskRunner.onCreated` (app-base) hands `TaskLaunch` the id its own create recorded; a failed start's cleanup uses
   only that id, and when its create made nothing while the name's record on disk names a live container, it touches
   nothing.

Seen red first, each against a deliberate break of its fix (copies taken and restored, `git diff --stat` the same):
both `HelperPidTest` await tests, all three tests above, both `VaultServePidFirstTest` tests,
`aStartThatLosesTheNameToAnotherStartStopsNothingOfThatOne` (it stopped the other start's container) and
`aHostStartRefusedForItsKeyIsRefusedAgainRatherThanResumed` (the second start tried to resume: 69, "there is no
container ... to resume").

Not in this change: the start's clearance watcher still only warns when it does not come up, and a vault broker whose
token never appears still lets the start go on; *What must be true* names both, the plan's step 3 only the gate and
relay.

To be checked: not touched. The signal and teardown path of an attached start is unchanged, so a resumed task ended by
`SIGTERM` leaving its helpers dead stays open.

## The reading's Reds, fixed (2026-11-13)

- A start whose vault broker or clearance watcher never comes up ends with 70 and leaves nothing of it running.
- A resume whose helper fails before the container starts never starts it. One that fails after is rolled back, and
  answers 70 with "was not resumed".
- A helper that ignores SIGTERM is killed, with its descendants, once its grace has passed (`HelperPid.end`).
- The created container's id is handed to the start before any record is written (`TaskRunner.recordCreated`).
- A start that loses the name to another ends the helpers it started itself, by their own handles.
- The wait for a helper's pid runs on monotonic time.
- Left as it was: a container that reports no pid still only warns about its watcher.
