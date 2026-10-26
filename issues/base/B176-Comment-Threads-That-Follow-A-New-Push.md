# [B176](README.md#b176) — Comment Threads That Follow A New Push

**Status:** Analyzed.
**Blocked by:** [B166](README.md#b166), [B291](README.md#b291).
**Blocks:** [B163](README.md#b163), [B289](README.md#b289).
**Size:** M - Git-object line mapping, durable pin/reconcile logic and bounded SARIF projection, with CLI/daemon rendering and acceptance.

**What must be true.** A line thread follows a new push of the same task only while its anchored bytes are unchanged,
including a shift or file rename. Changed lines, a missing file or a reused task name leave it on its pinned old
commit with an explicit reason, and every reader sees the same result.

## Plan

`GitGate.rankedReview` compares incoming work using Git objects and treats renames as deletion/addition for ranking.
`GitGate.reject` deletes the incoming ref; `GitHttpServer.hidingOthers` currently hides other incoming refs only.
[B166](README.md#b166)'s SARIF store, not a current `Thread` record, provides the relocation starting point.

1. Red first: `gate/.../ThreadMoveTest` creates two real commits with an isolated Git configuration. Lines 5-6 move
   to 7-8 after insertion above; editing even whitespace within the anchor keeps it as `linesChanged`; rename moves
   it; deletion keeps it as `fileGone`; a changed recorded container ID keeps it as `taskGone`.
2. Add `ThreadMove` beside `ReviewComments`, and pinned-object diff/pin helpers in `GitGate`. Read rename/path
   metadata with NUL delimiters separately from patch hunks: `-z` does not NUL-delimit a unified patch. Map new-side
   anchors head to head and old-side anchors base to base. Use rename detection only for relocation; preserve the
   ranking's deletion/addition semantics. Compare anchored bytes, never sender fingerprints or a fuzzy match.
3. Reconcile on reads through `ReviewComments` under the store's lock. Use the recorded container ID, not the task
   name alone. Once outdated, do not retry automatic movement; resolved threads follow the same mapping rule. File
   comments follow only verified file identity/renames; general comments keep their review scope without line mapping.
4. Preserve the submitted comment's original run/anchor and daemon-owned relocation evidence. Project a moved thread
   into a run pinned to its current head and base, with updated location and daemon-generated
   `fingerprints["porelo/context/v1"]`. Never change a run's revision while leaving unrelated results at old revisions;
   split projections by applicable revision and page them. Use [B291](README.md#b291)'s closed scalar original-anchor
   fields and `porelo/outdatedReason` (`linesChanged`, `fileGone`, `taskGone`), plus `porelo/outdated`.
5. Pin every referenced head/base under hidden `refs/porelo/reviewed/` refs; keep them out of every task's
   advertisement and fetch access. Coordinate pins with the SARIF store and backup refs. Do not assume an unreferenced
   commit remains readable after reject or rewritten history.
6. A moved fix is revalidated against the current blob and resolved replacement interval before it can be offered.
   Identical line text does not preserve a prior release or prove applicability. Outdated/old-side fixes stay
   discussion only. Replies may name a previously authorized anchor but cannot move the current thread or alter
   its state. Preserve each reply's own revision.
7. `GateMethods`, `35-gate.varlink`, `GateReviewCommand`, `gate comments` and reject delivery render the same bounded
   SARIF projection: where a thread moved from, or why it stayed. Journal each transition once. Update
   `doc/commands.md`; the acceptance scenario inserts lines, then edits the anchor and checks both results.

## Acceptance

- Seen red first: clean shifts/renames move, any anchored byte change stays, and name reuse cannot inherit threads.
  Old-side mapping uses the correct pinned base. General/file comments do not acquire invented line coordinates.
- Original comments stay bound to their revision; moved and old results cannot share a falsely stamped run. A
  stale suggestion, replayed release or forged fingerprint cannot authorize an edit.
- Reject, history rewrite and concurrent reads preserve anchors and record one transition. No task can list or
  fetch hidden review refs. CLI, daemon and delivered review data agree.

## To be checked

- Measure diff/reconcile bounds for many files and threads, including rename ambiguity; hold ambiguous mappings
  rather than guessing.
- Verify pin release only after active and archived consumers no longer need the objects, including
  [B289](README.md#b289) removal and [B163](README.md#b163) discarded attempts.
