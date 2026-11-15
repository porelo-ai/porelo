# B791 — A Stage Task Reads What the Planner Gives and Pushes Nothing

```json
{
  "$schema": "issue-1.json",
  "id": "B791",
  "title": "A Stage Task Reads What the Planner Gives and Pushes Nothing",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "A task can be started on one repository read only, with no gate and no push, and given files to read, such as the issue's current text from the planner, under /run/porelo/given/; a read-only task that hands back a result file is told that its work is that file alone",
  "openQuestions": 0
}
```

**Why this size.** L: a read-only start on one repository beside the existing one on all, an added start field with
its bounds and checks, a read-only mount beside the result box, the guide's words for a task whose work is its result
file, the contract and `doc/`, with their tests. The result file exists already. Estimate: 114 minutes (Core's median
for L).

**What must be true.** A planned stage that only thinks (analysis, estimation) runs as a task that reads its project's
code, reads the issue as the planner holds it now, writes one result file, and changes nothing in any repository. The
planner, not the agent, saves what the stage found.

Filed 2026-11-15 as porelo's part of the operator's story "A planning stage hands back its result, and the planner
stores it" (lead Agent Planner, with Agent Frontend), from the live test on a rented host the same day.

## Why

An In-analysis task wrote its plan into the repository's `issues/` itself and pushed it through the gate. Its copy
was stale, so it wrote a second issue file. The planner then moved the issue on while central's file held no plan.
The agent wrote the Git store's file format itself, past the planner's store; a store that is not Git could never
work that way.

Read in the code, most of the task side exists:

- `Start.allRepositories` gives a task every repository of its project read only, without `.git`, with no gate and no
  push target, and its end asks nothing about work.
- `Start.result` gives it the writable box `/run/porelo/result/` (`TaskResult.MOUNT`), takes the file at its end, at
  most 1 MiB of UTF-8, and `Result(task)` answers it to the window (`TaskResult`, `TaskRunner`, `TaskGuideText`).

What is missing (after Sonnet's reading, 2026-11-15): `allRepositories` refuses `repository` (`TaskLaunch`) and runs
with the own repository's agent, image and egress, so an issue of another repository, as porelo-pi's PI68, would be
analysed in the wrong image; a read-only start on one repository is missing. Also missing: a way to give the task the
issue as the planner holds it, rather than from the repository copy, and a guide that tells such a task that its
result file is its whole work.

## Plan

1. A start field `readOnly: ?bool` beside `repository`: that repository alone at its main as this machine takes it,
   under `/workspace/<repository>/`, read only and without `.git`, with its own agent, image and egress, no gate and
   no push target, and an end that asks nothing about work, as `allRepositories` ends. Refused with 64 beside
   `allRepositories`, `branch`, `from`, `noGate` or `upstream`; a task is brought back only as the kind it was made
   (the check `allRepositories` has). Also `porelo task start --read-only`.
2. A start field `given: ?[string]string`: file name to UTF-8 text, the names as `result` takes one (a file name, no
   directory part, never `.` or `..`), at most 16 files, each at most 1 MiB, and all together, as encoded JSON, under
   the 4 MiB a varlink message may have (`VarlinkConnection.MAX_MESSAGE`), else refused with exitCode 64 before
   anything is made, in `dryRun`, `CanStart` and `StartCheck` too. Also `porelo task start --given <name>=<file>`; a
   remote start (`RemoteStart`, over ssh) carries the contents on its input, never as arguments. Not offered through a
   task's MCP start (`McpTaskSockets` strips it as it strips `allRepositories`).
3. The host writes them, owner-only, in a record beside the task's result record (`TaskPaths`), and mounts them read
   only at `/run/porelo/given/` (`TaskRunner`, beside the result box). A task brought back keeps what it was given; a
   new creation of the name forgets it.
4. `TaskGuideText`: a task started with `readOnly` or `allRepositories`, and `result`, is told that its work is the
   result file alone. Its repositories are read only and nothing it changes there is kept, committed, pushed or
   reviewed, and it never edits `issues/`. A task started with `given` is told the files and that they are the
   current text, newer than any copy of the same file it reads under `/workspace/`.
5. `15-task.varlink` (`Start.readOnly`, `Start.given`, the task's row saying it is read only), `doc/running.md` and
   `doc/reach.md` (a row under what a task can read), and a changelog entry under *Added*.
6. What the result holds (`analysis.json`, `estimate.json`) and its validation are the planner's; porelo hands back
   the text as it does `idea.json`.

## Red-first test

- A read-only start on a repository that is not the project's own: its image and egress, its main read only without
  `.git`, no gate made, no push target. Fails today: no such field; `allRepositories` refuses `repository`.

- `TaskGivenTest` over a stand-in task: two given files are read back under `/run/porelo/given/` read only, and a
  write there fails. Fails today: no such field.
- A name with a directory part, a 17th file, one over 1 MiB, or text that is not UTF-8: refused with 64, nothing made.
- The guide of a task started with `readOnly` and `result` says the result file is its whole work and names no
  push; with `given` it names the files. Fails today: neither line.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, an In-analysis task started by the window reads the issue it was given, writes
  `analysis.json`, ends, and no commit or pull request appears on any forge.

## As built

- Contract first, handed over locally to the window and the planner: `Start.readOnly`, `Start.given`, the same two on
  `CanStart`, `StartOutcome` `READ_ONLY_REFUSED` and `GIVEN_REFUSED`, and `Task.readOnly` (true for `allRepositories`
  too).
- `TaskGiven` (app-base) keeps the given files owner-only beside the result record; `TaskRunner` mounts them read
  only at `/run/porelo/given/`. `TaskResult`'s name rule is shared by both.
- `TaskRepositories` knows two read-only kinds, all repositories and one; `WorkspaceSetup.readAll` reads a list of
  repositories, so the one-repository kind reuses the all-repositories machinery with its own repository's agent,
  image and egress. `TaskLaunch` refuses both kinds beside the options they cannot take, and a task brought back
  only as the kind it was made (`otherKind`).
- `TaskGuideText`: a read-only task with a result is told the file is its whole work, with no commit, push or edit
  of `issues/`; a task with given files is told they are the current text.
- `porelo task start --read-only` and `--given <name>=<file>`; the daemon's `Start` and `CanStart`; not offered
  through a task's MCP start (`McpTaskSockets`).
- Plan changed on two points: a remote start (`--host`) refuses `--given` with 64, as carrying the files on the
  input would share it with the certificate exchange; a task brought back refuses any `given`, not only other files.
- `doc/running.md`, `doc/commands.md`, `doc/reach.md` (a new row), a changelog entry under *added*.
- Red first: `TaskGivenTest` (12, against stubs), `StartCheckTest`, `TaskGuideTest`, `TaskRunCommandTest` (12, e.g.
  a read-only start on another repository with no read-only workspace mount), `McpTaskSocketsTest`, `TaskRowsTest`,
  and the doc test.
