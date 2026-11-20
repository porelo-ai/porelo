# B807 — A Stage Ended In A Task Is Measured Into Its Tracked Issue

```json
{ "$schema": "issue-1.json", "id": "B807", "title": "A Stage Ended In A Task Is Measured Into Its Tracked Issue",
  "state": "Analyzed", "size": "L", "priority": "High", "rank": 6, "blockedBy": [],
  "covers": "A task given work ends a stage by one trailer on the commit it pushes; porelod measures the stage from the task's own record, keeps it with an id, and answers a task's or a project's stages to the person's planner, which reads them and writes the tracked issue; porelo never calls the planner, and nothing goes to `stats/` in Git" }
```

**When.** The cut-over goes ahead without stage figures on GitHub items; this is built first right after it, with
the planner's read of the stages.

**Why this size.** It crosses repositories: the stage line and the read's shape are contracts with the planner; in
porelo the gate, porelod's task record, the Tasks1 contract, one command and their docs change, with an acceptance
scenario.

**What must be true.** In a project whose issues are kept in a tracker, an agent in a task that ends a stage leaves
nothing about it in Git: porelod measures the stage itself and keeps it, and the person's planner reads it from
porelod and writes it to the tracked issue as it keeps stages there. porelo never calls the planner and depends on
nothing of it.

## Plan

- **How a task says a stage ended: a trailer on the commit that ends it**, `Porelo-Stage: <stage> <result>`, beside
  the `Porelo-Issue: <issue>` line a task given work already carries on every commit. A stage ends with a push in
  every phase the planner knows (the issue's state is set in the same commit as its last code), so the gate, which
  already reads each pushed commit's trailers, needs no new channel, and a task without an MCP endpoint can say it
  too. `<stage>` is one lower-case word (the planner's stage names, such as `analysis`, `build`, `reading`, `fix`,
  `review`, are its own; the gate knows none), `<result>` one of `done`, `blocked`, `failed`. A malformed
  `Porelo-Stage` line refuses the push as the missing issue line does.
- **What is measured is the host's, not the agent's.** porelod's task record (task, work, agent, model, effort,
  start, end, tokens per request) gives the stage: its start is the task's start or the previous stage's end in the
  same task, its end the moment the gate took the push, its tokens those of the requests in between, by model and
  kind. Only the stage name and the result come from the commit. A stage a task reports twice is one stage, its
  first push.
- **porelod keeps each stage in the task's record**, kept past removal as the record is, in the shape agreed with
  the planner: `id` (a random UUID given once, never reused), `item` (the tracker reference
  `<owner>/<repository>#<number>`, the task's `work.issue`, which the `Porelo-Issue` line already names), `stage`,
  `result`, `start` and `end` (UTC instants), and `tokens`, a list of `{model, input, cacheWrite, cacheRead, output}`.
  Agent, model and effort are the record's own. There is no VM-minutes member: porelod cannot see a VM a task used,
  and the planner writes 0.
- **porelod answers the stages; the planner reads them, porelod never sends them.** `TaskRecord` gains `stages`, so
  the read the planner already makes, `Records(project, since)` and `porelo task records --project <p> --since
  <time> --json`, carries them unchanged in its form. Its `since` already covers every stage: a stage ends no later
  than its task, so one that ended at `since` or later belongs to a task that did too, or works still. The planner
  writes each stage as the item's stage comment keyed by `id` and skips an id already there, so a repeated read
  writes nothing twice; porelod keeps no mark of what was read, pushes nothing and retries nothing.
- **A project without `tracker`** keeps its stages the same way; whoever reads them decides.
- **The seed** is [B809](README.md#b809)'s `velocity.seed` in the project's file, which the planner reads; nothing
  here reads it.
- **Steps**: `IssueTrailers` grows the stage line; the gate's judge hands each accepted stage to porelod; porelod
  measures it from `TaskWorkRecords` and the usage record and keeps it in the task's record; the Tasks1 contract
  (`TaskStage`, `TaskRecord.stages`); `doc/running.md` (a task given work),
  `doc/commands.md` and the contract page say so; changelog entry under *Added*.
- **Red first**: an `IssueTrailersTest` case refuses a malformed stage line; a measurement test over a fixture task
  record expects the stage's start, end, minutes and tokens from the record, not from anything the commit says, and
  a second push of the same stage keeps one; a `Records` test answers a removed task's stages, oldest first, with ids
  unchanged between two reads. One acceptance scenario: a task given work pushes a stage end and `porelo task
  records --json` answers it with its id and item.

## Acceptance

- After a task ends a stage, `Records` and `porelo task records` answer it once, with an id that does not change, and
  the repository's commit range holds no `stats/` change; the minutes match the task record within a minute.
- A push whose stage line is malformed is refused, and no stage is kept.
- porelo holds no planner client, address or key.

## To be checked

- A task on a Porelo host away from the planner: the planner reads that host's porelod as the window reaches it;
  whether the window's way to a host's Tasks1 serves the planner as it is, or what is missing, read in the host's
  code before this is built.
