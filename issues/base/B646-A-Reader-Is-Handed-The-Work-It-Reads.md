# B646 — A Reader Is Handed The Work It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "B646",
  "title": "A Reader Is Handed The Work It Reads",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task started to read another task's work is given that work through its own gate, read only and under a name its order can use, and nothing else of other tasks; today no task can see another task's branch.",
  "openQuestions": 0
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

**Size.** M: the gate, its serve command, the start's option and record, Tasks1 Start and List, and the docs; one
contract change the window and the planner read.

## Plan

Read in the code on 2026-11-12 (main 120805914). The mirror is the project's, shared by its tasks. A task's gate serves
it through `git upload-pack` with `transfer.hideRefs` (`GitHttpServer.hidingOthers`): every `refs/porelo/incoming/`
but the task's own, and every `refs/porelo/forwarded/`. The refresh drops every task branch from the mirror
(`GitGate.dropApprovedBranches`, `taskBranch`). When the gate forwards a task's work, it records the commit under
`refs/porelo/forwarded/<task>/<hash of upstream and branch>` (`GitGate.java:1457`, `forwardedRef`). The name does not
say the branch, and the record is hidden from every task. The List row already carries `forgeBranch`, the branch the
start settled. A person may name another branch at approval, so the branch the work landed on is the forward's own.

1. **Red first.** In `GitGateTest`, a builder's approved work and a reader started naming the builder:
   - the reader's `git ls-remote origin` lists `refs/porelo/landed/<builder>/<branch>` at the forwarded commit;
   - a third task started without naming the builder lists nothing of it;
   - work still held at the gate is listed for nobody;
   - the reader's push to that ref is refused.
2. **The landed record.** Where the gate forwards (`:1457`), it also writes `refs/porelo/landed/<task>/<branch>` at
   the forwarded commit. The record names the branch, and the commit is the one forwarded: in `guarded` the reviewed
   one, in `online` the task's own as its class allows. It is removed with the task's other refs.
3. **Hidden, then named.** `hidingOthers` hides `refs/porelo/landed/` for every task. For each task the reader names,
   it unhides `refs/porelo/landed/<task>/` (git matches at a component boundary, so `foo-1` does not reveal `foo-10`).
   `git receive-pack` already takes only the task's own incoming ref, so the reader cannot write there. A test pins it.
4. **The start.** `task run --reads TASK` (repeatable) and Tasks1 Start's `reads: [string]`. Each name must be a task of
   the same project and repository, else 64. The names are kept in the task's profile, passed to its `gate serve` as
   `--reads`, and kept on resume. The reader's workspace gets the fetch refspec
   `+refs/porelo/landed/*:refs/remotes/origin/landed/*`, so `git fetch` brings `origin/landed/<task>/<branch>`.
5. **The row.** List's and Watch's row of a task whose work landed carries `landed: [{branch, ref, commit}]`, read
   from its `refs/porelo/landed/<task>/` records. The planner takes `ref` into the reader's order. `forgeBranch` stays
   as it is.
6. **Docs.** `doc/how-it-works.md` (gate) and the Tasks1 reference name `reads` and `landed`.

## Acceptance

- The four cases of step 1, each seen red first.
- A reader started by Tasks1 Start with `reads` finds the builder's work with `git ls-remote origin` and
  `git fetch`. A task started without `reads` finds none of it, as today.
- List names `landed` for the builder after its approval, and nothing while its work waits at the gate.

## Answered in the analysis

- **Forge branch or reviewed commit:** the commit the gate forwarded, under the branch it landed on. In `guarded`
  that is the reviewed commit. The forge is not read, so a later push by someone else to that branch is not shown
  as the task's work.
- **A reader of a whole project** (`--all-repositories`, a reading copy without `.git`): not in this issue. Its copy
  has no gate to serve refs through. If it needs one, it is its own issue.

## To be checked

- What removing the builder does to its `landed` records while a reader still runs. The plan keeps them until the
  builder is removed; a reader started after that refuses the name with 64.

## Decided by the operator (2026-11-12)

- Accepted, priority Highest, with Estimated and Backlog given in advance for its analysis (ST314, run 26).
