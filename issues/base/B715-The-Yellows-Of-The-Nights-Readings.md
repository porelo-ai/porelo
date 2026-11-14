# B715 — The Yellows Of The Night's Readings

```json
{
  "$schema": "issue-1.json",
  "id": "B715",
  "title": "The Yellows Of The Night's Readings",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 69,
  "blockedBy": ["B622"],
  "covers": "An agent's default provider is one it knows, the start check's refusal of an unknown provider is tested and names where the provider came from, `porelo keep` never leaves an older work record than the one it was given, and a task that wrote no result costs a listing nothing.",
  "openQuestions": 0
}
```

**Why this size.** Small changes in five places, each with its test.

Filed 2026-11-13 from the Sonnet readings of the night's work, where nothing was found to stop a merge. Two of the
changes read are not on main yet: a result taken only from a task that ran, and Agent Smith's tests under any login
and umask. Smith files the latter's points himself.

- **A native agent and a provider it does not know.**
  - Nothing checks that `provider.default` is among `knows`, so an agent definition can refuse its own default on
    every start. Refuse such a definition when it is read.
  - The new `StartCheck` arm for `UNKNOWN_TO_AGENT` has no test of its wire value and exit code.
  - Its refusal says `--provider` when the provider came from the project's setting. Name where it came from.
- **[B622](README.md#b622).**
  - A restore can leave an older work record than the kept one. Machine B restores an early keep, the task goes on on
    A, A keeps again, and B's restore keeps its own older record as "newer". Compare by the record's own time, not
    by which copy is on the machine.
  - The "no records were kept" message names only the usage path.
  - A linked or unreadable `*.json` gives exit 70, where the documentation says it is "not copied".
- **A result taken only from a task that ran.** A kept `MISSING` is retaken at every listing, with its lock and a
  rewrite of the record, for a task that wrote nothing. Skip the write when the new record equals the kept one. No
  test covers `Initialized`, an empty status or two retakes at once.

**What must be true.** Each point above holds, with a test that fails without it.

## Acceptance

- Each point's test holds and fails without its change.

## Built

- `AgentProvider` refuses a nonempty `provider.knows` that leaves out `provider.default`, at the constructor shared
  by YAML and JSON reading. A null default and an empty list retain their behavior.
  `AgentDefinitionReaderTest.refusesADefaultProviderOutsideTheAgentsKnownProviders` and
  `aDefaultOutsideTheKnownProvidersIsRefusedOnTheWireToo` expect an exception that the starting code never threw;
  `aKnownProviderListMayLeaveTheChoiceToTheCaller` pins the valid null default.
- `TaskRunCommandTest.aProviderUnknownToTheAgentIsATypedStartRefusalAndLeavesNothingBehind` pins
  `UNKNOWN_TO_AGENT`, its wire value and names, exit 2 from both a dry run and an actual start, and no image build or
  container creation. `TaskLaunch.refusesModel` now reports a provider refusal instead of swallowing it after
  `CredentialChoice` marked the choice resolved. The actual-start assertion is expected to fail without this fix;
  mapping the `StartCheck` arm to `WRONG_DIALECT` also makes the outcome and wire assertions fail.
- **Provider provenance: no change.** This checkout has no project provider-selection setting; its `providers` map
  selects models. With the default-provider validation built above, the refused
  selection comes from `--provider`, so the existing diagnostic names the available source correctly.
- `TaskWorkRecords` writes an `updatedAt` instant on every original record write, including starting, ending,
  resuming, stopping and removal. Keep and restore preserve that instant and select the later update of the same
  task. A record without the field is older than one with it; between two legacy records the machine's copy still
  wins. Equal update times leave the destination alone, and an invalid instant is refused. Retirement cannot delete
  a live copy with a newer or equal update time. `Tasks1.TaskRecord` declares optional `updatedAt` for older records.
  - `KeepCommandTest.aSecondRestoreTakesTheWorkTheOriginalMachineKeptAfterTheFirstRestore` plays the two-machine
    sequence: an early keep is restored elsewhere, the original machine keeps later work, and the second restore
    must replace the early copy. Its final content assertion is expected to fail on the starting code.
  - `aWorkRecordWithAnOlderUpdateCannotReplaceANewerCopy`,
    `aTimestampedWorkRecordTakesPrecedenceOverALegacyCopyInEitherDirection` and
    `equalWorkRecordUpdateTimesLeaveTheDestinationAlone` cover keep and restore. Their keep cases or timestamped
    restore cases are expected to fail under the old machine-wins rule. The existing legacy keep and restore tests
    still pin behavior when both copies lack the field.
  - `aRemovedWorkRecordCannotRetireANewerLiveCopy` and
    `aRemovedWorkRecordCannotRetireADifferentLiveCopyWithTheSameUpdateTime` protect live work from retirement; the
    old identity-only deletion loses those copies.
    `anOlderRemovedSourceStillRetiresTheLiveCopyHeldByTheNewerKeptRecord` keeps the latest removed copy and removes
    the older live duplicate even when the source's removed copy is older than the one already kept.
  - `aWorkRecordWithAnInvalidUpdateTimeIsRefusedWithoutReplacingKeptWork` expects exit 70 and preserved kept work;
    the old copy accepted the invalid field and replaced it.
  - `TaskUsageTest.everyWorkRecordWriteCarriesItsOwnUpdateTime` replaces each prior time with an early marker and
    checks that every write, including a resumed record without an end, supplies a valid new instant. The starting
    code has no `updatedAt` at all. `InterfaceDescriptionTest.workRecordsDeclareAnOptionalUpdateTime` pins the field's
    optional wire declaration, absent from the starting contract.
- The empty-restore diagnostic names the supplied directory and both usage and work records.
  `KeepCommandTest.aRestoreFromADirectoryNothingWasKeptInIsNamed` now requires both kinds; the old diagnostic named
  only the usage path.
- Linked or unreadable work-record sources are named and skipped successfully. Readable malformed records and
  destination failures remain errors, and a skipped removed source cannot retire kept live work.
  `KeepCommandTest.aLinkAmongTheWorkRecordsIsNotFollowedOffTheMachine`,
  `aLinkAmongTheKeptWorkRecordsIsNotRestored` and `anUnreadableWorkRecordIsSkippedWhileTheOthersAreKept` expect zero
  where the starting code returned 70. `aSkippedRemovedWorkRecordCannotRetireTheKeptLiveRecord` also pins the kept
  live file that the old retirement loop deleted. `aLinkWhereAKeptWorkRecordGoesStillFailsAndLeavesItsTargetAlone`
  pins destination failures separately. The unreadable-source test skips when the test account can read a file
  without read permission, and always restores its permissions.
- `TaskResult` checks the newly taken value against the kept one under its existing lock and skips equal writes;
  missing remains provisional so a later file is still taken. `TaskInventory` does not infer an end from an empty
  runtime status. `TaskResultTest.repeatedListingsOfATaskThatWroteNothingLeaveItsKeptMissingAlone` pins a deliberately
  old modification time; the starting code rewrites it. `aTaskNotKnownToHaveRunIsListedWithoutTakingAndItsEndHandsTheFile`
  covers `Created`, `Initialized` and empty status, then a real end; empty status previously took a premature missing.
  `twoRetakesOfAKeptMissingKeepTheSameWholeResult` checks both concurrent readers and the saved content. Initialized
  and concurrent retakes add coverage of behavior already present.

**Validation:** no Maven, compilation or tests ran in the sandbox. Java additions were formatted with the cached,
repository-pinned google-java-format 1.36.0 in AOSP style; unrelated baseline formatting was preserved. All red-first
statements above are expectations from code reading, not observed failures. `git diff --check` passes. Read-only code review covered
the changes; its equal-time retirement and missing wire-field findings were checked and fixed with the tests above.
Skills applied: `graal` 2026.910.3; `java-code-review`, `test-quality`, `security-audit`, `concurrency-review`,
`clean-code` and `solid-principles` 2026.906.3.

**Test classes for the build box:** `AgentDefinitionReaderTest`, `KeepCommandTest`, `TaskResultTest`,
`TaskRunCommandTest`, `TaskUsageTest` and `InterfaceDescriptionTest`. Regression classes: `StartCheckTest`,
`SelectedProviderTest` and `TaskStopTakesResultTest`. None of these classes is tagged `slow`; no `-Pwhole` is needed
to include their tests. The whole suite and the required checks remain for the build box.
