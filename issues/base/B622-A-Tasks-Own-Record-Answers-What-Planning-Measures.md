# B622 — A Task's Own Record Answers What Planning Measures

```json
{
  "$schema": "issue-1.json",
  "id": "B622",
  "title": "A Task's Own Record Answers What Planning Measures",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "porelod keeps one record per task, kept past its removal, with its project, issue and phase, its start and end, its agent, model and effort, and the tokens counted per request; Tasks1 `Records` and `porelo task records --json` answer a project's records since a time."
}
```

**Why this size.** one record written at start and end beside [B148](README.md#b148)'s usage file, one varlink method and one command
over it, the contract page; unit tests over the existing usage fixture.

Filed 2026-11-11 as this repository's part of `porelo-project` ST320, "The work tools live with their users", and of
ST24 "The tokens counted per task": the gap between what [B148](README.md#b148) records and what planning needs to measure a stage
exactly (PJ38, PL61).

**What [B148](README.md#b148) records today.** Per request, in the task's usage record (one JSON line each): `time`, `agent`,
`model_answered`, `input`, `cache_read`, `cache_write`, `output`, `reasoning` and `end` (complete, aborted, error).
At removal the record is kept beside it as `<container>.removed-<time>`. Tasks1 `Usage(task)` and `porelo task status`
answer its totals, by model, agent, day and hour. So tokens per kind, cache tokens and the model answered are there.

**What is missing for an exact measurement.**

- **Wall time.** A task's start and end: `Task.since` and `Task.ended` answer them only while the task exists; once
  removed, only the usage record is left, and it holds request times, not the task's life.
- **What the task worked on.** Nothing names the issue and the phase a task was started for (PJ38: one task, one
  issue, one phase). A start's parameters are the project's own declarations, not this.
- **Effort.** The reasoning effort the agent was started with is not recorded.
- **A project's records at once.** `Usage` takes one task by name, and a removed task's record can only be named by
  its container; planning needs every task of a project since a time, removed or not.

## Plan

- Start takes `work: ?(issue: string, phase: string)` (Tasks1) and `--work <issue>:<phase>` (CLI), the phase a
  state name of one line and at most 64 characters, kept with the task as given.
- porelod writes one task record at start and completes it at the end and at removal: `task`, `project`,
  `repository`, `work` (issue, phase or null), `agent`, `model`, `modelFrom`, `effort`, `startedAt`, `endedAt`,
  `end` (done, stopped, removed, failed), and the totals of [B148](README.md#b148)'s usage record. It is kept past removal as the
  usage record is.
- Tasks1 `Records(project: string, since: string) -> (records: []TaskRecord)` and `porelo task records --project <p>
  --since <time> --json` answer them, oldest first. The planner reads them through the daemon's socket or the
  command; never the files.
- Red first: a `TaskUsageTest` case starts, works and removes a task with `--work B622:Implementing` and expects its
  record with start, end, work, effort and totals; `Records` answers it after the removal.
- Changelog entry under *Added*; `doc/commands.md` and the Tasks1 contract name the fields.

## Settled with Agent Planner (PL61)

- The phase is a working state of the project's own workflow (`stories/workflow.yml`, kind WORKING), such as
  `Implementing`, `Reading` or `Reworking`, named by the planner at Start beside the issue. Porelo keeps it as given and
  does not know the set: one line, at most 64 characters.
- Minutes are wall time, from the task's start to its end. Busy time (from the request times) may be an extra field;
  the velocity does not use it.
- The planner reads `Records(project, since)` on the daemon's socket, as the person, and no file.
