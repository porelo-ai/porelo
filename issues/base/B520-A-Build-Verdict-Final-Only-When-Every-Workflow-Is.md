# [B520](README.md#b520) — A Build Verdict Final Only When Every Workflow Is

**Status:** Backlog. Filed 2026-11-08 from the third move's findings 13b, 16 and 20 (the operator's decision, 19:38Z).
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
