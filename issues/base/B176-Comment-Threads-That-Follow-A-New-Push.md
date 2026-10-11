# B176 — Comment Threads That Follow A New Push

**Status:** Analyzed; blocked by sokar [B166](README.md#b166).
**Size:** M - a line mapping and its store update in the gate, a pinned ref per commented commit, two fields and an
enumeration added to [B166](README.md#b166)'s `Thread` in `35-gate.varlink`, the review's rendering, docs and an
acceptance step; no other repository's contract changes but the interface and the IDE plugin reading the new fields.

**What must be true.** A comment thread on waiting work moves with a new push of the same task to the lines it was on,
instead of staying on the commit it names, as it does under [B166](README.md#b166). It moves only when those lines are
unchanged in the new push - shifted by lines added or removed above them, or in a file that was renamed. Where any of
them changed, the file is gone, or the name now belongs to another task, the thread stays on its old commit and says
which of these it is. A person, the agent and a reviewer task see the same thread in the same place.

## Why

A rework push is what a reject asks for, so under [B166](README.md#b166) every thread a person opened ends up "on an
earlier commit" after the very push that answers it, and the person reads the new work with the comments beside the old.
Moving the threads that still fit, and saying plainly which do not, keeps the review on the work that waits.

## Plan

Read in today's code: a task pushes only to its own `refs/sokar/incoming/<name>` (`GitHttpServer`, `--ref` from
`GateWiring`); a later push of the same task moves that ref, and a reject deletes it (`GitGate.reject`), so a rework
push after a reject creates it again under the same name. [B166](README.md#b166)'s store keys threads by that name
(`sokar/comments/<push name>/`), and its anchor names the pushed commit, with an `old` side read at the review's base.

- **When a thread moves:** when its threads are read - `ReviewComments.threads(name)`, which [B166](README.md#b166)'s
  `Comments`, `gate review`, `gate comments` and the reject's delivery all go through - and the name's incoming ref
  holds a commit other than the thread's anchor. Reading at that point needs no hook in the per-task git server, which
  faces the agent and stays as narrow as it is. The reconcile takes a lock file in the push's comment directory, so the
  CLI and the daemon never write a thread twice.
- **How lines are mapped:** `git diff -U0 --find-renames <old commit> <new commit>` read with `-z`, as `rankedReview`
  reads its output. A `new`-side anchor follows its path through a rename and shifts by the lines the hunks above it add
  or remove; if a hunk touches any of its lines, or the file is deleted, it stays. An `old`-side anchor is mapped the
  same way from the old review base to the new one (`merge-base` with the review branch, as `GitGate.compared`), which
  is the identity while the base has not moved. Since a moved thread's lines are exactly the same text, a suggestion on
  it still fits its anchor and is not checked again.
- **Which task:** a thread records the recorded container id (`SokarContext.recordedId`) of the task its push came from,
  when its first comment is made. A thread moves only while the name's task still has that id; a later task of the same
  name - possible once the ref was rejected and the task removed - never inherits it, and the thread stays as
  `TASK_GONE`.
- **Once stayed, it stays:** a thread that stayed is not tried again on a later push, so where it is does not change
  back and forth. Resolved threads move as open ones do.
- **The commit stays readable:** a reject deletes the ref and a rework push may rewrite history, after which
  `receive-pack`'s automatic `gc` prunes the unreachable commit after two weeks (a bare mirror keeps no reflog). Every
  commit a thread names is pinned by a ref `refs/sokar/reviewed/<name>/<commit>`, written with the thread, which
  `GitGate.pending` never lists, the backup bundle carries (`refs/*`), and `GitHttpServer.hidingOthers` hides from every
  task with one more `transfer.hideRefs`. [B166](README.md#b166) already needs this to show a thread on an earlier
  commit; if it is built without it, this adds it.
- **The contract,** in `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/35-gate.varlink`, only growing: `Thread`
  gains `from: ?Anchor` (the anchor of its first comment, when it has moved since) and `stays: ?ThreadStays`, with `type
  ThreadStays (LINES_CHANGED, FILE_GONE, TASK_GONE)`; `anchor` is always where the thread is now. Each `Comment` keeps
  the anchor it was written on.
- **A reply on a moved thread** (way B, and a reviewer task's under [B172](README.md#b172)) is accepted with any anchor
  the thread has had, since the agent may answer from the message the reject delivered before its rework push; the reply
  keeps its own anchor, and the thread's stays where it is.

Steps:

1. **Red first:** `gate/src/test/.../ThreadMoveTest.java` - from the hunks of a real two-commit repository (git with
   `GIT_CONFIG_GLOBAL=/dev/null`, as `GitGateTest`): an anchor on lines 5-6 with two lines inserted at line 1 moves to
   7-8; one with line 6 edited stays as `LINES_CHANGED`; one in a renamed file moves to the new path; one in a deleted
   file stays as `FILE_GONE`; an `old`-side anchor with an unchanged base keeps its lines.
2. **`gate/.../ThreadMove.java`:** the mapping, a pure function of the anchor and git's `-U0 -z` output, beside
   [B166](README.md#b166)'s `ReviewComments`; `GitGate` gains the diff call and the pin (`pin(name, commit)`,
   `unpin(...)`).
3. **`ReviewComments`:** the reconcile under the lock, the task id on a thread, `from` and `stays` in the thread's JSON
   file, and one journal line per move or stay in `sokar/comments/journal.log` (thread, from, to or why, when).
4. **`GitHttpServer.hidingOthers`:** hides `refs/sokar/reviewed/`; `GitGateTest` holds that a task's advertisement names
   none.
5. **The daemon and the CLI:** `GateMethods` fills the two fields; `SokarDaemonTest` and `InterfaceDescriptionTest` hold
   the type and the enumeration; `GateReviewCommand` and `gate comments` print a moved thread under its new line with
   "moved from <short commit> line N", and a stayed one among those on an earlier commit with its reason in words. The
   reject's note (`PersonNote.rejectedWork`) gives each thread's current anchor and, for a stayed one, its reason.
6. **Docs:** `doc/commands.md` (`gate review` and `gate comments`: when a thread moves and when it stays).
7. **Acceptance:** in `acceptance/suite/.../gate/gate-review.feature`, with the stub: a push, a person's comment on line
   5, a second push of the same task adding two lines at the top - `gate comments` shows the thread on line 7 of the new
   commit, moved from line 5; a third push editing that line - the thread stays on the second commit and says its lines
   changed.

## Acceptance

- Seen to fail first, then green: after a second push that only adds lines above it, a thread shows on the new commit at
  its shifted lines with where it came from - and, before the change, it showed on the first commit as
  [B166](README.md#b166) leaves it.
- A thread whose lines were edited, whose file was deleted, or whose name now belongs to another task stays on its
  commit and names which of the three; its lines are still shown from that commit after the ref was rejected and pushed
  again.
- No task's `git ls-remote` against its gate lists a `refs/sokar/reviewed/` ref.
- `doc/commands.md` says when a thread moves and when it stays.

## Open questions

- **How strict "unchanged" is.** Proposal: the anchored lines must be byte for byte the same; a whitespace-only change
  to them counts as a change, so the thread stays as `LINES_CHANGED`. It is the rule a person can predict, and it keeps
  a suggestion valid wherever a thread moves. The alternative - also moving across a whitespace-only change, or an edit
  within a line - would move threads more often and need a suggestion checked again after each move.

## To be checked

- When a push's threads, and so their pins, end: [B166](README.md#b166) says nothing of removing them at approve or when
  a task's name is forgotten (`GitGate.forget`); the pins go wherever its store's threads go (Agent Core's, with
  [B166](README.md#b166)).
- How long the reconcile's `git diff` takes on a push with many changed files, measured, and whether it should be
  limited to the paths the threads name.
