# B807 — A Stage Ended In A Task Is Measured Into Its Tracked Issue

```json
{ "$schema": "issue-1.json", "id": "B807", "title": "A Stage Ended In A Task Is Measured Into Its Tracked Issue",
  "state": "Analyzed", "size": "L", "priority": "High", "rank": 6, "blockedBy": [],
  "covers": "A task given work ends a stage by one trailer on the commit it pushes; the host measures the stage from the task's own record and sets the tracked issue's stage comment and fields through the tracker, never `stats/` in Git; `project.yml` names the starting minutes per repository and size under `velocity.seed`" }
```

**Why this size.** It crosses repositories: the stage's shape and the seed key are contracts with the planner, and
the write to the tracker goes through its interface; in porelo the gate, porelod's task record, the project file and
their docs change, with an acceptance scenario.

**What must be true.** In a project whose issues are kept in GitHub Issues, an agent in a task that ends a stage
leaves nothing about it in Git: its measurement (stage, result, start, end, minutes, agent, model, effort, tokens by
model and kind) lands on the tracked issue as the planner keeps stages there - one stage comment, and the item's
number fields as their sums - and the planner computes each size's median from the project's finished items, starting
from the minutes per size the project's file names until enough are measured. Nothing about issues is kept in Git any
more.

## Plan

- **How a task says a stage ended: a trailer on the commit that ends it**, `Porelo-Stage: <stage> <result>` (the
  stage a phase name of one line, the result one word such as `done` or `stopped`), beside the
  `Porelo-Issue: <issue>` line a task given work already carries on every commit. A stage ends with a push in every
  phase the planner knows (the issue's state is set in the same commit as its last code), so the gate, which already
  reads each pushed commit's trailers, needs no new channel, and a task without an MCP endpoint can say it too. The
  gate parses it with the issue trailer's walk; a malformed `Porelo-Stage` line refuses the push as the missing issue
  line does.
- **What is measured is the host's, not the agent's.** porelod's task record (task, work, agent, model, effort,
  start, end, tokens per request) gives the stage: its start is the task's start or the previous stage's end in the
  same task, its end the moment the gate took the push, its tokens those of the requests in between, by model and
  kind. Only the stage name and the result come from the commit. A stage a task reports twice is one stage, its first
  push.
- **Who writes to the tracker.** porelod hands the measured stage, with the item's tracker reference
  (`<repository>#<number>`), to the planner's tracker interface on the host, which writes the stage comment and sets
  the number fields in one save, as only the planner may. The tracker's key stays on the host (the person's vault, as
  the forge credential); a task never holds it. A write that fails is kept and retried, and shown on the task, never
  dropped; nothing is written into the repository.
- **A project without `tracker`** has no planner keeping its issues: the stage line is checked as any trailer and
  written nowhere.
- **The seed key**, agreed with the planner: a top-level `velocity` in the project's file, which the planner's
  carry-over writes once from the current medians and the person changes and signs afterwards; porelo checks its form,
  keeps it through every rewrite and reads nothing in it:

  ```yaml
  velocity:
    seed:
      porelo: { XS: 30, S: 120, M: 480, L: 1440 }   # the starting median per size, in minutes
  ```

  Each key under `seed` is one of the file's repositories; each size one of `XS`, `S`, `M`, `L`, `XL`, any of them
  missing; each value a whole number of minutes from 1 to 100000. Only in the project's file, never in a
  repository's own `porelo.yml`, as `tracker`. Today any key porelo does not know is refused where it stands, so a
  file that holds `velocity` before this is built no longer reads and every start of the project is refused: the
  key is built here first, and the planner writes it only after. `Sections`, `FileBinder`, `ProjectFile`,
  `ProjectFileCheck`, `FileYaml` and `ProjectSchema` take it as the `tracker` key was taken; `doc/project-file.md`
  describes it.
- **Steps**: `IssueTrailers` grows the stage line; the gate's judge hands each accepted stage to porelod; porelod
  measures it from `TaskWorkRecords` and the usage record and calls the tracker interface; the project file takes `velocity`; `doc/running.md` (a task given work), `doc/project-file.md` and
  `doc/security.md` (the tracker key never in a task) say so; changelog entry under *Added*.
- **Red first**: an `IssueTrailersTest` case refuses a malformed stage line; a measurement test over a fixture task
  record expects the stage's start, end, minutes and tokens from the record, not from anything the commit says; a
  stand-in tracker interface sees exactly one save per stage and a retried one after a failure; a
  `ProjectFileCheckTest` case refuses a `velocity.seed` with an unknown repository, an unknown size, zero and a
  fraction, and one in a repository's own file. One acceptance
  scenario: a task given work pushes a stage end and the stand-in tracker on the test host holds its stage.

## Acceptance

- After a task ends a stage, the tracked issue shows one stage comment and the summed fields, and the repository's
  commit range holds no `stats/` change; the minutes match the task record within a minute.
- A push whose stage line is malformed is refused, and nothing reaches the tracker.
- A project without `tracker` takes a stage line and writes nothing anywhere.

## To be checked

- With Agent Planner: the tracker interface porelod calls on the host (library, command or daemon method), and that
  a stage porelod measured is written exactly as the planner's own stage comment and fields; the stage line's words
  and the results it knows.
- With Agent Gorge: whether the GitHub write goes through porelo-forge-clients' GitHub module behind the planner.
- What carries VM minutes, which porelod cannot measure for a task: left out, or reported by the task and marked so.
