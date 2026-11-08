# [B520](README.md#b520) — A Build Verdict Final Only When Every Workflow Is

**Status:** Review. Filed 2026-11-08 from the third move's findings 13b, 16 and 20 (the operator's decision, 19:38Z).
**Size:** S - `BuildWatch` keeps watching a commit after its first final verdict.

**What must be true.** Within the decided design (doc/running.md "builds": no forge credential in a task, Porelo
reads from the host and replaces `build-<commit>.txt` as the verdict changes):
- A commit's verdict reaches the task as final only once no workflow of it is running or queued, and a workflow that
  starts later, such as a Build started by hand, is followed again: its verdict replaces the file.
- A commit stays watched after a final verdict, until `BuildWatch.DEADLINE` after its last change, while the task runs;
  a resumed task takes up a finished commit within that time too.

## Why

On porelo-move-3 the Shared rules check finished first, and each task was told `verdict: success` with that one
workflow, while `build.yml` had not started (16). When the Build was started by hand and passed, no task was told
(20): `BuildWatch.build` removes a commit from `watched` at its first finished verdict, and `resume` drops a finished
one.

## As built

- `BuildWatch` keeps a commit in `watched` after a finished verdict, asks it every `SETTLED` (2 min) and leaves it
  `DEADLINE` (2 h) after the last change of what the task was told; a workflow that starts after a final verdict
  reopens it at `POLL` and its own deadline. `resume` takes up a finished commit as final.
- A refusal or a shut vault no longer tells a final commit "unknown": what the forge says when asked is its verdict,
  what keeps Porelo from asking is not.
- Tests in `BuildWatchTest`: a later workflow is followed and replaces the file, a finished commit is left at its
  deadline, a resumed watch takes up a finished commit (three seen red against main), and a refusal leaves a final
  verdict as it was. doc/running.md says it.
- Whether a verdict covers every workflow of the commit is the reader's; Frontend names the workflow on the tile
  (finding 16).
- After its reading (agy gemini-4-argon-high, read-only, on 6022b78e7; each finding checked in the code):
  - `resume` settles a commit by its last record line, so one that ran again after a final verdict is asked every
    round again, not every two minutes.
  - A final verdict whose hand-in failed is not settled. The next round hands it in, so it cannot reach the deadline
    untold.
  - A forge that later answers `unknown` for a final commit leaves the task's verdict as it was.
  - Tests, each seen red: `BuildWatchTest.aResumedWatchAsksAgainEveryRoundForACommitWhoseLastLineIsRunning`,
    `aFinalVerdictWhoseHandInFailedIsAskedAndHandedInAgainAtTheNextRound` and
    `aForgeThatLaterKnowsNoBuildLeavesAFinalVerdictAsItWas`.
  - Left as it was: `resume` takes the head from the last record line (from 2026-10-08, not [B520](README.md#b520)'s).

