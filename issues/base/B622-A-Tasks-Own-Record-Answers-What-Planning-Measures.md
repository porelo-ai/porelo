# B622 — A Task's Own Record Answers What Planning Measures

```json
{
  "$schema": "issue-1.json",
  "id": "B622",
  "title": "A Task's Own Record Answers What Planning Measures",
  "state": "Review",
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
- `ToolEvent(line, id, phase: START|END, tool, kind, command: ?string, background: bool, jobId: ?string, at: ?string,
  name: ?string, parent: ?string, durationMs: ?int)`. The mapping is per line and holds no state; porelod joins a START to its END by
  `id`, and a background job's start and end by `jobId`. In the Java API, `Agent#toolEvents(String line)` with a
  default that reads nothing.
- `name` is, for a `sub-agent` START, the name the sub-agent was given; `parent` is the id of the sub-agent call a call
  ran under (in one adapter's stream, `parent_tool_use_id`). Inside a task each issue goes to a sub-agent named by the
  issue's id (the operator's decision, 2026-11-11), so porelod gives every entry the `issue` of the sub-agent it ran
  under, following `parent` up through nested ones; a call outside any sub-agent has `issue` null.
- `durationMs` is, on an END, how long the call took as the stream says (agreed with Agent Smith, 2026-11-11). porelod
  takes an end from the stream's `at`, else the START's time plus `durationMs`, else the line's arrival; a start from
  the stream's `at`, else, where the END has `durationMs` and the START no stream time, the END's time minus it, else
  the arrival. Each entry says the source of its start and of its end: `stream`, `duration` or `arrival`.
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
- A credential shape is masked inside a longer word of a command line too, so a base64 run that holds `sk-` by chance
  loses that run; over-masking in a command line is accepted, and only command lines are masked, never the stream's
  other text (the operator's decision, 2026-11-11).
- Every non-blank value of the vault is masked, however short (the operator's decision, 2026-11-11).
- Red first: a unit test over lines with each kind of secret, the vault's own value among them, expects none of them
  in the stored entry.

## Settled with Agent Planner (PL61)

- The phase is a working state of the project's own workflow (`stories/workflow.yml`, kind WORKING), such as
  `Implementing`, `Reading` or `Reworking`, named by the planner at Start beside the issue. Porelo keeps it as given and
  does not know the set: one line, at most 64 characters.
- Minutes are wall time, from the task's start to its end. Busy time (from the request times) may be an extra field;
  the velocity does not use it.
- The planner reads `Records(project, since)` on the daemon's socket, as the person, and no file.

## As built

- Agent1 version 5 (`LOWEST` 3) adds `ToolEvents(lines)` and `ToolEvent` in `porelo-agent-api`, with `name`, `parent`
  and `durationMs` added on the way; `InstalledAgent.toolEvents` answers an adapter without the method as empty,
  which the record says as `CANNOT_SAY`.
- `CommandMask` in the task area is the one place a command line is masked: the vault's values (every non-blank one)
  and the run's own, then key blocks, credential shapes (inside a longer word too), a URL's user information,
  `Authorization:`, environment names ending in `_TOKEN`, `_KEY`, `_SECRET` or `_PASSWORD`, password, token, secret
  and key options, `-p` of the MySQL tools and `sshpass`, and `curl -u`'s password. A vault that cannot be read masks
  every command line whole.
- An agent may name a call's job only on a later line: a second START for a known call id that carries a `jobId`
  registers the job for that call, and its `background: true` marks the call as background; nothing else of it
  counts. The job's END then ends the call, after a call's early result too. A job's first END is its call's end; a
  repeated END of the same job, as an agent's progress lines give it, changes nothing.
- `ToolCalls` joins a START to its END by id and a background job by `jobId`, takes each time from the stream, else
  from the other end and `durationMs`, else the line's arrival, and gives an entry the `issue` of the sub-agent it ran
  under, the name matched as an issue's id (uppercase letters and digits).
- The run's log is always followed while a prompt runs, so tool events are read as lines arrive; lines are shown only
  to a caller that follows.
- `TaskWorkRecords` keeps the record at `work/<container>.json` in the state directory, written at creation,
  completed at each run's end, at a stop and at removal (from `TaskUsage.archive`, before the usage record is kept),
  and renamed `<container>.removed-<time>.json`. `Records` answers live totals for a task still there.
- `effort` is null until Start has an effort setting. `Records` is a method a task's endpoint never carries.
- `porelo keep` does not yet copy the work records; `CanStart` does not take `work`.
- Red first: `CommandMaskTest` (19 of 23, then the glued-credential cases), `ToolCallsTest` (4 of 4, then the two
  duration cases, then the three job cases), `ToolEventsTest`, `TaskUsageTest`'s record case and `TaskRecordsMethodTest`.
- After agy's read-only review (gemini-4-argon-high), each finding checked against the code:
  - A call given a job on a later START ends with the job alone, whatever the START says of the background: its
    early return, before or after that line, is no longer its end, and a run that ends first leaves it open.
  - A job id used again belongs to the call that took it last.
  - A foreground call's first END is its end.
  - `curl -u "user:pw"` is masked as `curl -u 'user:pw'` was. An `Authorization:` header with a scheme other than
    Bearer, Basic, Token or Digest is masked with its scheme.
  - A later run in the same container is masked by the values the task was started with too
    (`TaskLaunch.startValues`, from its `parameters.json`).
  - The record is written once nothing can refuse the start any more, after the host's forge token. A start the host
    refuses leaves no record.
  - The records are written under a file lock in `work/`, across processes: porelod and a `porelo task stop` both end
    one. `Records` reads under it, and a record gone meanwhile is left out.
  - `TaskRunCommandTest.aStartedTasksRecordSaysItsWorkItsModelAndHowItsRunEnded` runs the whole start. It fails with
    the start's `recordStart` taken out.
  - Rejected: `_KEY` assignments are masked already. A start time taken from a duration is right. A run in an
    existing task replaces the record's end, as one record per task says.
  - Left for later: `--work` given to a task that exists already is not taken.

## Finished points (2026-11-13)

- `porelo keep <dir>` copies the work records, `work/*.json` in the state directory and the removed tasks'
  `.removed-<time>.json`, into `<dir>/work/`, and `--restore` puts them back, under the lock their writers hold. The
  usage records' rules hold, adapted to a record rewritten whole rather than appended to: a removed task's record is
  copied first and drops the copy kept under the task's own name when that is the same task (the same `task` and
  `startedAt`); a copy of the same task takes the machine's at a keep, and a restore leaves the machine's own, which
  is never older; a record of another task under the same name is named and both stay (exit 70); a file that is no
  work record, a link, the lock and a staged write are not copied; a directory kept before the work records is
  restored as it is.
- Tasks1 `CanStart` takes Start's `work: ?(issue, phase)` and answers the new `StartOutcome` `WORK_REFUSED`, in the
  words of `TaskWork.refusal`, before anything else, as Start refuses it with exitCode 64 (`StartCheck.check` with
  the work). An added enum value and an optional field: no break within Tasks1.
- Red first: `KeepCommandTest`'s eight work-record cases (seven red; the one over a store kept before the work records
  passed already; a ninth, a fresh machine's directory being the account's alone, came after), and `PoreloDaemonTest.aWorkStartRefusesIsRefusedByCanStartBeforeAnythingStarts` (CanStart answered
  `NO_AGENT` for an empty phase).
- Left: Start's `works` ([B630](README.md#b630)) is not taken by CanStart yet; it follows once it lands.
