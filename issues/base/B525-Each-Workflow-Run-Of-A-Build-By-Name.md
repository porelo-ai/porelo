# [B525](README.md#b525) — Each Workflow Run Of A Build By Name

**Status:** Backlog. Filed 2026-11-08 from move 3's finding 16; the operator decided one line per workflow on the tile.
**Size:** M - Build2 and Tasks1 each gain a list, BuildWatch carries it; the readers fill it (Agent Adapters).

**What must be true.** A build's answer names each workflow run of the commit with its own state at every answer,
also while queued or running, not only once the verdict is final. The tile shows one line per run. A workflow that
has not started is not shown, since no forge knows it.

## The shape, for Agent Adapters

Build2, beside `jobs`, absent from a reader from before:

```
# One run of a workflow or pipeline for the commit: its name as the forge spells it, and what became of it in lower
# case, or its state while it is not finished ("queued" | "waiting" | "in_progress" | ...). A rerun replaces its run.
type Run (name: string, result: string)

method Build(...) -> (verdict: string, jobs: []Job, runs: ?[]Run, detail: string)
```

Tasks1's `Build` gains `runs: []BuildRun` (`name`, `result`), empty when the reader gave none, and the
`build-<12>.txt` handed into a task names each run in one line. At most 50, in the forge's order.

## Plan

1. `builds/api` `Build` gains `List<Run> runs` and `record Run(String name, String result)`, at most `MOST_RUNS` = 50.
   The constructors from before stay, with `runs` empty. `toMap` writes `runs` only when non-empty. `fromMap` reads
   it when present, skips an entry without a string `name` and `result`, cuts at 50, and cleans each text as a job's
   name is cleaned.
2. `org.fuin.porelo.Build2.varlink`: `type Run (name: string, result: string)`; `Build(...)` answers
   `runs: ?[]Run`. The protocol version is unchanged: the field is optional both ways.
3. `StubReader` answers `runs` from its JSON at every answer, also while queued or running, so the acceptance
   tests can drive it.
4. `BuildWatch.tell` also tells when `runs` changed (same names and results in the same order counts as unchanged).
   So a run's change of state is handed in like a verdict's, at most once per round of 20 s. `resume` takes `runs`
   back from the record.
5. `TaskBuilds.Seen` gains `runs` (record and Tasks1 `Build.runs: []BuildRun`, empty when absent). A record line from
   before reads with none.
6. `BuildStatus` writes one line per run into `build-<12>.txt` (`run      <name>: <result>`), each name and result
   shown as a job's are.
7. Tests, red first: the `Build` round trip with and without `runs`; the stub answering two runs while running; a
   watched running build with one run finished and one running reaching `TaskBuilds` and the file; a run's change
   alone handed in once; a record line from before read with no runs.

## Why

`jobs` is empty while queued or running (Build.java), so the window names workflows only once a verdict is final, from
the jobs' `<workflow> / <job>` names.

## Acceptance

- Red first: a running build with two runs, one finished, reaches Tasks1 with both runs and their states.
- A reader that answers without `runs` still gives a build, with `runs` empty.
