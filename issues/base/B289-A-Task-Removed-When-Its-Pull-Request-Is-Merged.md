# [B289](README.md#b289) — A Task Removed When Its Pull Request Is Merged

**Status:** Idea.

**What must be true.** An enabled project ends and removes a task after an authorized person merges its exact
reviewed PR head, only when no unmerged work or pending review operation can be lost. The daemon verifies the merge
from authenticated forge state and explains every hold or removal.

## Proposed

- Blocked by: [B171](README.md#b171), [B176](README.md#b176), [B293](README.md#b293).
- Blocks: none.
- Off unless the project enables it. The daemon fetches state through the host adapter; events only wake it.
  Require a merged PR for the task's mapped repository/branch, an authenticated authorized human merger and the
  exact reviewed source head. Compare the merged source head, not the resulting merge/squash commit ID; preserve
  evidence when the forge cannot prove it. Comments, SARIF verdicts and unverified event bodies never prove a merge.
- Use [B293](README.md#b293)'s per-item source evidence and approval requirements. An agent account opening the host's
  Forgejo PR does not become the person who accepts it. A closed PR, failed build, new push or unknown merger cannot
  trigger removal.
- Check current container work and stopped-task notes through `TaskControl` and `UnhandedWork`, including changes
  after the merged head. `TaskControl.remove` refuses a running task by default, so automatic completion needs an
  explicit stop followed by recheck and safe removal, never a force that discards work. Missing/unreadable evidence
  holds the operation and says why.
- Check [B166](README.md#b166)/[B171](README.md#b171)'s store and outbox before destructive cleanup. Unreleased or unsent
  outbound items hold removal. Archive the SARIF journal and retain every pin needed by active or archived evidence;
  unpin only when the retention owner has released its last reference, not merely because a task disappeared.
- Show the project setting and authenticated merge evidence that caused removal. Recover safely after restarts and
  use recorded container IDs, so a task reusing the name cannot inherit the removal.

## Open questions

- Which human source levels may authorize automatic cleanup: I1 only, or E1 under an explicit project rule?
- Does a task reserved for further work on the same branch need a per-task opt-out?

## To be checked

- Verify merge/squash/rebase head evidence and merger identity in GitHub and Forgejo adapters. Neither `Build2` nor
  `BuildWatch` currently reports PR merge state; [B171](README.md#b171)'s adapter contract must carry that evidence.
- Exercise stop/recheck/remove races with new local commits, dirty files, missing stopped notes, pending replies
  and a moved task identity. Do not attribute work-retention checks to the running-task diff issue.
