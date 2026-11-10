# B607 — A Missing Builds Credential Names Its Forge

```json
{
  "$schema": "issue-1.json",
  "id": "B607",
  "title": "A Missing Builds Credential Names Its Forge",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": [],
  "covers": "The wire's `MissingCredential` names the forge whose builds the missing entry reads, so the window can say which forge."
}
```

**Why this size.** one field on one contract type, filled from the `builds.forge` the check already holds; tests.

Filed 2026-11-10 from porelo-frontend's F443 Push ready (To be checked, not blocking), on the coordinator's order
of 15:02Z. Implementing since 2026-11-10. Built by Core; in Review since 2026-11-10, until the operator's test.

**What must be true.** A `MissingCredential` (`CanStart`'s `missing`, the task's `buildsCredential`) names the forge
whose builds the entry reads (`forge`, as the project's `builds.forge` gives it), besides the vault key, so the window
can say which forge, as F443's acceptance asks. A task's record kept before the field still offers its remedy, with
`forge` "".

## Plan

- `MissingCredential` gains `forge`; `BuildsWiring.missing` fills it from `builds.forge()`; `read` takes a record
  without it as "".
- `15-task.varlink`'s `MissingCredential` gains `forge: string`.
- Red first: `PoreloDaemonTest`'s `canStart` answers and `BuildsWiringTest`'s task row expect `forge`;
  `TaskBuildsTest` reads an older record.

## As built

- `MissingCredential` has `forge` between `credential` and `storeCommand`; `BuildsWiring.missing` fills it from
  `builds.forge()`, so `CanStart`'s `missing` and the task's `buildsCredential` both carry it. `read` takes a task
  record without the field as `forge` "", and its remedy stays.
- `15-task.varlink` declares `forge: string`; `doc/running.md` names it; changelog `missing-credential-forge.yml`.
- Red first: `BuildsWiringTest.aTaskKeepsTheMissingCredentialAndItsRestartRemedy` (no `forge` in the row), then
  green with `PoreloDaemonTest`'s two `canStart` answers, `InterfaceDescriptionTest` and
  `TaskBuildsTest.aMissingCredentialKeptBeforeItNamedItsForgeReadsWithNone`. wire, apps/app (4298) and daemon whole:
  green.
