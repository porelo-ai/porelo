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

## Why

`jobs` is empty while queued or running (Build.java), so the window names workflows only once a verdict is final, from
the jobs' `<workflow> / <job>` names.

## Acceptance

- Red first: a running build with two runs, one finished, reaches Tasks1 with both runs and their states.
- A reader that answers without `runs` still gives a build, with `runs` empty.
