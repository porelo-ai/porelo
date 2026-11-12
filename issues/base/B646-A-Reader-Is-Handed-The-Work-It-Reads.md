# B646 — A Reader Is Handed The Work It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "B646",
  "title": "A Reader Is Handed The Work It Reads",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task started to read another task's work is given that work through its own gate, read only and under a name its order can use, and nothing else of other tasks; today no task can see another task's branch."
}
```

Filed 2026-11-12 under ST314 from porelo-frontend F492 run 26, step 9. The reader `porelo-foo-project_foo-1-2`
was ordered to read the builder's work on `porelo/foo-1`. It polled `git ls-remote origin` for about 30 minutes and
saw 2 refs every time, also long after `porelo/foo-1` stood on the forge. It said "No `porelo/foo-1` branch exists on
the remote yet" and never ended. (Frontend's files: porelo-frontend/run26-reader-foo-1-2.)

How it is today, read in the code: by design, a task never sees another task's branch.
- The gate's refresh fetches `refs/heads/*` from the forge and then drops every task branch, meaning every ref under
  `porelo/` or named by the project's branch scheme (`GitGate.taskBranch`: "never read by or handed to a task").
- On top of that, the gate hides every other task's incoming ref and the forwarded records (`transfer.hideRefs`).
- So a reader's `origin` carries the default branch and its own ref, and the builder's branch is out of its reach by
  any name. That rule keeps one task's unreviewed work from another. A reader whose job is to read a builder's work
  needs a named exception to it.

The planner's order says "its work on the branch porelo/<t>". The order cannot be right while porelo hides that
branch, so the fix is porelo's. The planner's order follows whatever name porelo gives; it now names only "the work of Porelo task <t>".

**What must be true.**
- A task can be started to read named tasks' work: on the CLI and in Tasks1 Start, as the planner starts a reader.
  Its gate then advertises, read only, those tasks' branches as they stand on the forge after review, under the names
  its order uses. It sees nothing of any other task, as today.
- Work still held at the gate (not reviewed, not forwarded) is not shown. The reader is offered only after the
  builder's row says `waiting` 0 (see the planner's part).
- A push by the reader to such a branch is refused.
- `git ls-remote origin` in the reader lists the branch, so a model's own check finds it.
- Tasks1 List's row (and Watch's) of a task whose work landed carries the branch it landed on, beside `waiting`.
  The branch follows the project's branch scheme (`ApprovalBranch.of`), which only porelo knows. The planner takes
  it with ENDED into the reader's order (Agent Planner, 2026-11-12 14:37Z). The field alone does not let a reader
  reach the work; the start naming the tasks does.

**The planner's side** is `porelo-planner` [PL90](https://github.com/porelo-ai/porelo-planner/blob/main/issues/README.md#pl90)
"A Reader Is Started With The Work It Reads": it takes the branch from the task's row with ENDED into the reader's
`{tasks}`, and the reader's offer names the builders' tasks, so the window starts it naming them.

Open questions, for the plan reading:
- Branches as they stand on the forge (forwarded), or the reviewed commit named by porelo?
- Whether a reader of a whole project (`--all-repositories`, a reading copy without `.git`) needs the same, and in
  what form.
