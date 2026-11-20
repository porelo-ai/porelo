# B806 — A Task Given Work Pushes Only Commits Naming Its Issue

```json
{ "$schema": "issue-1.json", "id": "B806", "title": "A Task Given Work Pushes Only Commits Naming Its Issue",
  "state": "Review", "size": "M", "priority": "High", "rank": 5, "blockedBy": [],
  "covers": "a task given work is told the trailer `Porelo-Issue: <tracker reference>` and its gate refuses a push with a commit that lacks it; the work's issue is the tracker's reference; built on the cut-over day" }
```

**Why this size.** A new judge in the gate, its wiring from the task's start to the gate's process, the work's issue
taking a tracker's reference and kept across a stop, judges composed and serialized per ref, the task's guide, docs
and one acceptance step: several modules and a contract another repository reads. The plan reading added the last
three, so it sits at the top of M, close to L.

**What must be true.** Once a project keeps its issues in a tracker, every commit made for an issue names it by a
trailer below the subject, `Porelo-Issue: <tracker reference>` (for GitHub `Porelo-Issue: porelo-ai/porelo#123`), one
line per issue and no closing keyword. A task started for an issue is told that exact line, and its gate refuses a push
that carries a commit without it, so no work reaches review that does not say which issue it is for.

## Plan

- **The contract.** A task's work stays `work: (issue, phase)` in `Tasks1.Start` and `--work <issue>:<phase>`; once
  the issues are in a tracker, `issue` is the tracker's reference, such as `porelo-ai/porelo#123`, never a second name
  beside it, so there is one name for the issue. What changes is what `issue` may hold, by its own rule apart from
  `phase` (which stays one line of at most 64 characters): at most 200 characters (a GitHub owner and repository
  alone take up to 140) of printable ASCII, with no space, colon or backtick and no leading `-`, since the trailer's
  value, a markdown code span in the guide and an option of the gate's process all carry it as it stands. The rule is
  `TaskWork`'s, so `--work`, `Start` and `CanStart`, a child start through a task's MCP socket included, all hold to
  it. The varlink comments of `work` say so. Neither Frontend nor Planner sends anything new: whoever starts a task for
  an issue in a tracker passes that issue's reference as `issue`, and they follow the new limit.
- **The work outlives a stop.** The task's work is kept with its durable state, so a task brought back or restarted
  after a reboot has the same issue, its guide the same line and its gate the same judge; the guide of a task brought
  back reads the issue from its recorded gate's command line, the one its gate judges by. `--work` on an existing task
  that is not the one it was made with, issue and phase, or that `TaskWork` refuses, is refused (exit 64), as
  `--given` is.
- **The task is told the line.** The guide's shared opening stays the same for every task, as prompt caching needs;
  a section of its own after it gives a task with a gate and given work the exact line `Porelo-Issue: <issue>` and
  says that a push with a commit lacking it is refused. A task without work, a reading task and one without a gate
  are told nothing of it.
- **The gate refuses.** `TaskLaunch` passes the work's issue to the task's `porelo gate serve` as `--issue=<issue>`,
  which checks it by the same rule. The judge walks every commit the push's commit reaches that no branch, tag or
  landed read (`refs/porelo/landed/*`) holds, as `SignedArrivals` walks them, capped the same way, and an empty walk
  counts only for a commit a ref already holds; it refuses a ref pointing at anything but a commit. The task's rescue
  ref is not judged, as by the signature judge: Porelo pushes the container's only copy there before removing it, a
  refusal would put that copy at risk, and the review shows it as rescued. It reads each commit's trailers as git
  parses them, with `-z` records and git's own configuration shut out (`GIT_CONFIG_GLOBAL=/dev/null`,
  `GIT_CONFIG_NOSYSTEM=1`), and requires a trailer line exactly `Porelo-Issue: <issue>` as git prints it (git prints
  `Porelo-Issue:<issue>` so too, as every tool reading trailers through git sees it); a commit may name further
  issues by further lines. The refusal names the first commit without it and the line it needs.
- **Both judges hold.** `GitHttpServer.judging` today keeps only the last judge given: it takes them in order, the
  first refusal wins, and a gate without a certificate still records an accepted push as unsigned while the issue
  judge's refusal is recorded as refused. Pushes to one task's refs are judged one after another: today a second push
  can move the ref while the first is judged, so the first's refusal cannot put it back and the second judges only
  from the first's commit; this holds for the signature judge too, and the lock covers both. A push waits at most five minutes for another
  and is then refused. Only what receive-pack wrote is judged, put back or passed on, read from the mirror, since a
  push's own `<old> <new>` words reach git's arguments: each side must be an object name, and a command receive-pack
  refused is never judged. A judge that cannot answer refuses, and the ref goes back; the walk's git must exit zero.
- **Docs**: `doc/running.md` (the work, its rule and its trailer), the gate's page on what a push may carry, a
  changelog entry.
- **Tests, red first** - unit (`gate`): a push whose every commit names the issue is taken; one commit without it,
  one with `porelo-issue:`, one with the line in the body but not as a trailer, one with
  only another issue, are refused by name; a first push (old ref zero) and a push merging central's main or a landed
  read are judged right; two pushes at once to one ref, the first lacking the line, end with the ref back and neither
  taken; a tag is refused; a gate without an issue judges nothing, and both judges must hold. `TaskWork`: a reference
  of 200 characters is taken; 201, a space, a no-break space, a colon, a backtick or a leading `-` are refused; a phase
  keeps its rule. A task brought back keeps its work. Acceptance (`task-unattended.feature`): a task given work pushes
  a commit without the trailer and is refused, then with it and is taken.

## Acceptance

- On the VM, a task given work is refused a push of a commit without `Porelo-Issue: <its issue>` and takes one with
  it; the guide shows the line; seen red with the judge left out.

## To be checked

- Whether the review page (`GitGate.made`) should show the trailer beside each commit; it lists trailers already.
