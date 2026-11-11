# B622 — A Task's Own Record Answers What Planning Measures

```json
{
  "$schema": "issue-1.json",
  "id": "B622",
  "title": "A Task's Own Record Answers What Planning Measures",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "porelod keeps one record per task, kept past its removal, with its project, issue and phase, its start and end, its agent, model and effort, and the tokens counted per request, and one entry per tool call in a format porelod defines and each provider adapter maps its stream into; Tasks1 `Records` and `porelo task records --json` answer a project's records since a time."
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
- **What the agent did.** Nothing records the agent's tool calls: which tool, of which kind, from when to when, and
  for a shell call the program it started and whether it ran in the background. A provider's own stream shows them,
  each in its own shape, and is gone with the task.
- **A project's records at once.** `Usage` takes one task by name, and a removed task's record can only be named by
  its container; planning needs every task of a project since a time, removed or not.

## Plan

- Start takes `work: ?(issue: string, phase: string)` (Tasks1) and `--work <issue>:<phase>` (CLI), the phase a
  state name of one line and at most 64 characters, kept with the task as given.
- porelod writes one task record at start and completes it at the end and at removal: `task`, `project`,
  `repository`, `work` (issue, phase or null), `agent`, `model`, `modelFrom`, `effort`, `startedAt`, `endedAt`,
  `end` (done, stopped, removed, failed), and the totals of [B148](README.md#b148)'s usage record. It is kept past removal as the
  usage record is.
- The record also holds one entry per tool call: `task`, `tool` (the agent's own name for it), `kind` (`shell`,
  `edit`, `read`, `sub-agent`, `search`, `web`, `other`), `start` and `end`, for a shell call its `command`, the whole
  command line masked by porelod before it is stored, and `background` for one that ran in the background, whose start and end are then
  joined by the job's `id`. porelod defines this common format in the Tasks1 contract; each provider adapter (Agent
  Smith's five repositories) maps its own stream into it, and the planner reads it through `Records`.
- Tasks1 `Records(project: string, since: string) -> (records: []TaskRecord)` and `porelo task records --project <p>
  --since <time> --json` answer them, oldest first. The planner reads them through the daemon's socket or the
  command; never the files.
- Red first: a `TaskUsageTest` case starts, works and removes a task with `--work B622:Implementing` and expects its
  record with start, end, work, effort and totals, and its tool calls from a stand-in adapter's stream, one shell call
  in the background joined by its id; `Records` answers it after the removal.
- Changelog entry under *Added*; `doc/commands.md` and the Tasks1 contract name the fields.

## Tool calls, as the operator added them (2026-11-11)

- One entry per tool call, in porelod's common format; porelod keeps no provider's own shape.
- A call that ran in the background has two moments: its start, and the end of its job, joined by the job's id. Its
  `end` is the job's end, not the moment the call returned.
- Each provider adapter maps its own stream into the format; a kind it cannot tell is `other`, never left out.
- The size may no longer be M once analysed: the format, the adapters' part and its tests are new work.

## The adapters' seam, settled with Agent Smith

- An adapter is its own executable behind `org.fuin.porelo.Agent1`, so the seam is a method of that contract: version
  5 adds `ToolEvents(lines: []string) -> (events: []ToolEvent)`, a batch like `FormatLog`, each event naming the index
  of the line it came from. `LOWEST` stays 3: an adapter without the method answers `MethodNotFound`, and porelod
  records "this agent cannot say", never an empty list as if no tool ran.
- `ToolEvent(line, id, phase: START|END, tool, kind, command: ?string, background: bool, jobId: ?string, at: ?string)`.
  The mapping is per line and holds no state; porelod joins a START to its END by `id`, and a background job's start
  and end by `jobId`. In the Java API, `Agent#toolEvents(String line)` with a default that reads nothing.
- `kind` is a closed set in the contract: `shell`, `edit`, `read`, `search`, `web`, `sub-agent`, `mcp`, `other`; a
  tool the adapter does not know is `other`.
- `command` is a shell call's whole command line as the stream gives it; the adapter does not mask it. porelod masks it
  before anything is stored (see below).
- `at` is the stream's own time when it has one; else porelod stamps the line's arrival.
- Only an unattended run's output is read. An attended (tmux) session is not measured; the operator decides that later.

## The command line, masked (the operator's decision, 2026-11-11)

- The whole command line is kept, not only its program, and never carries a secret: porelod masks it once, in one
  place, before it is written, whatever the adapter. An adapter passes the line as its stream gives it.
- Masked: a token, password or key given as an option's value (`--password=…`, `--token …`, `-p…` where the tool
  takes a password so), an `Authorization:` header's value, the value of an environment assignment whose name ends in
  `_TOKEN`, `_KEY`, `_SECRET` or `_PASSWORD`, the user information of a URL, a string shaped like a known credential
  (provider keys, forge tokens, private key blocks), and every value of the vault porelod knows for the task.
- A masked value is replaced by `***`, its name and the rest of the line kept, so the line still says what ran.
- Red first: a unit test over lines with each kind of secret, the vault's own value among them, expects none of them
  in the stored entry.

## Settled with Agent Planner (PL61)

- The phase is a working state of the project's own workflow (`stories/workflow.yml`, kind WORKING), such as
  `Implementing`, `Reading` or `Reworking`, named by the planner at Start beside the issue. Porelo keeps it as given and
  does not know the set: one line, at most 64 characters.
- Minutes are wall time, from the task's start to its end. Busy time (from the request times) may be an extra field;
  the velocity does not use it.
- The planner reads `Records(project, since)` on the daemon's socket, as the person, and no file.
