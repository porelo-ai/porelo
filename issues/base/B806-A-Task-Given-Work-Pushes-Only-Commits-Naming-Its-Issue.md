# B806 — A Task Given Work Pushes Only Commits Naming Its Issue

```json
{ "$schema": "issue-1.json", "id": "B806", "title": "A Task Given Work Pushes Only Commits Naming Its Issue",
  "state": "Implementing", "size": "M", "priority": "High", "rank": 5, "blockedBy": [],
  "covers": "a task given work is told the trailer `Porelo-Issue: <tracker reference>` and its gate refuses a push with a commit that lacks it; the work's issue is the tracker's reference; built on the cut-over day" }
```

**Why this size.** A new judge in the gate, its wiring from the task's start to the gate's process, the work's issue
taking a tracker's reference, the task's guide, docs and one acceptance step: several modules and a contract another
repository reads, so M.

**What must be true.** Once a project keeps its issues in a tracker, every commit made for an issue names it by a
trailer below the subject, `Porelo-Issue: <tracker reference>` (for GitHub `Porelo-Issue: porelo-ai/porelo#123`), one
line per issue and no closing keyword. A task started for an issue is told that exact line, and its gate refuses a push
that carries a commit without it, so no work reaches review that does not say which issue it is for.

## Plan

- **The contract.** A task's work stays `work: (issue, phase)` in `Tasks1.Start` and `--work <issue>:<phase>`; once
  the issues are in a tracker, `issue` is the tracker's reference, such as `porelo-ai/porelo#123`, never a second name
  beside it, so there is one name for the issue. What changes is what `issue` may hold: one line of at most 200
  characters (a GitHub owner and repository alone take up to 140), without white space, since the trailer's value is
  that text as it stands; a colon cannot stand in it through `--work`, which splits at the first one, and a reference
  of the forges Porelo knows holds none. The varlink comment of `work` says so. Neither Frontend nor Planner sends
  anything new: whoever starts a task for an issue in a tracker passes that issue's reference as `issue`, and they
  follow the new limit.
- **The task is told the line.** `TaskGuideText` (`app-messaging`), where it says to commit and push, gives a task
  given work the exact trailer line and says that a push whose commits lack it is refused; a task without work is
  told nothing new.
- **The gate refuses.** `TaskLaunch` passes the work's issue to the task's `porelo gate serve`, as an option of its
  own; `GateServeCommand` (`app-gate`) puts a judge in front of the existing ones through
  `GitHttpServer.judging`: for every commit the push brings that the mirror's `main` does not hold (`<new> ^<old>
  ^refs/heads/main`), `%(trailers:key=Porelo-Issue,valueonly,unfold)` must hold a line equal to the issue. The
  refusal names the first commit without it and the line it needs, and the ref stays where it was, as with every
  judged refusal today. A task without work, a gate without an issue, and a merge commit's parents already on `main`
  are not judged. One `git log` for the whole push, as `GitGate.made` reads trailers, so a long push stays one process.
- **Docs**: `doc/running.md` (the work and its trailer), the gate's page on what a push may carry, a changelog entry.
- **Tests, red first** - unit (`gate`): a push whose every commit names the issue is taken; one commit without it,
  one naming another issue, one with the line in the body but not as a trailer, are refused by name; a task with no
  work is not judged. `TaskWork`: a reference of 200 characters is taken, 201 or one with white space refused.
  Acceptance (`task-unattended.feature`): a task given work pushes a commit without the trailer and is refused, then
  with it and is taken.

## Acceptance

- On the VM, a task given work is refused a push of a commit without `Porelo-Issue: <its issue>` and takes one with
  it; the guide shows the line; seen red with the judge left out.

## To be checked

- Whether the review page (`GitGate.made`) should show the trailer beside each commit; it lists trailers already.
