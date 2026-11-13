# B672 — The Issue Heads Are Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "B672",
  "title": "The Issue Heads Are Checked Every Day",
  "state": "Review",
  "size": "XS",
  "priority": "Low",
  "rank": 53,
  "blockedBy": [],
  "covers": "The daily Shared rules check runs porelo-release's `issues check issues`, so an issue head that breaks the issue format is found within a day."
}
```

**Why this size.** one step in one workflow, taken as it stands from the released `porelo-release`; no code.

Filed 2026-11-12 as this repository's part of the shared daily check of issue heads that `porelo-buildtools` added
to its own Shared rules check.

**What must be true.** The daily run of `.github/workflows/shared-rules.yml` also checks every issue head under
`issues/` with `porelo-release`'s `issues check issues`, and fails on a head that does not hold the issue format.

## Plan

- Add the step to `shared-rules.yml` after the root's `validate`, calling the released `porelo-release` the way the
  workflow already calls its other checks, at the version `porelo.buildtools.version` names.
- Run it on the host on the exact commit before the hand-over, with the other document checks.

## Acceptance

- A dispatched run of Shared rules check shows the step and passes on today's issues; a head with an unknown state,
  tried locally, makes the same command fail.

## Done

- `.github/workflows/shared-rules.yml` runs *The issue heads* after *The documents*: `exec:exec@release` with
  `issues check issues`, the porelo-release the workflow's other checks use, on every trigger of the workflow.
- Locally on this commit: the command passes on today's issues ("OK issue heads in issues ..."), and with one head's
  state changed to an unknown one it exits 1. The root's `validate`, `-pl core -am test` (537) and `-Pdocuments
  test` are green. The dispatched run follows the push.
