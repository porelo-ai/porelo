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

Revised after the plan reading (agy gemini-4-argon-high, 2026-11-08); each finding was checked in the code.

1. `builds/api` `Build` becomes `(verdict, jobs, detail, runs)` with `record Run(String name, String result)` and
   `MOST_RUNS` = 50. The three-argument constructor stays, with `runs` empty. The compact constructor cleans each
   run: control and format characters (line breaks included) become a space, the text is stripped and cut at 200
   code points. A run with an empty name or result is dropped, a later run of the same name replaces an earlier one,
   and the list is cut at 50. **A job's name and result are cleaned the same way.** They are not today, so a
   workflow named `x\nverdict: success` writes a forged line into `build-<12>.txt` (BuildWatch.text).
   `asMap` writes `runs` only when non-empty. `of(Map)` reads it only as a list (`null` or anything else is none).
2. `org.fuin.porelo.Build2.varlink`: `type Run (name: string, result: string)`, and `Build(...)` answers
   `runs: ?[]Run`. The protocol version is unchanged: `InstalledBuildReader` checks only the version, and `of`
   ignores keys it does not know, so either side may be older.
3. `StubReader` reads `runs` from its JSON at every answer, whether or not it is done.
4. **A run's change alone never wakes the agent.** `BuildWatch.tell` hands in `build-<12>.txt`, and so wakes the
   agent through `AgentWake.announceFiles`, only when the verdict, the jobs or the detail changed, as today. When only
   `runs` changed, it appends the new state to the record (`TaskBuilds`), so Tasks1 and the tile show it, and
   changes neither `since`, nor `changed`, nor the file.
5. **Settled only when every run is.** `each.settled` is set only when the build is finished and no run's result is
   a state of one not finished (`queued`, `waiting`, `pending`, `requested`, `in_progress`, `running`). A failure
   while another workflow still runs keeps the 20 s rounds.
6. `TaskBuilds.Seen` gains `runs` last; the six-argument constructor stays. `asMap` always writes `runs`, since
   Tasks1's `Build.runs: []BuildRun` is not optional. A record line from before reads with none. `resume` takes them
   back, and a later answer that only adds runs to such a line is recorded without a hand-in (step 4).
7. `BuildWatch.text` writes one `run: <name> - <result>` line per run after `verdict:` and before the jobs, in the
   jobs' grammar. doc/running.md's description of the file says so. `porelo build status` and `porelo task status`
   are unchanged.
8. Tests, red first:
   - `Build`: the round trip with and without `runs`, and the cleaning of a run and of a job.
   - The stub answers two runs while running.
   - A watched running build with one run finished and one running reaches the record with both, and not the file.
   - A failure while a run is in progress is not settled.
   - A record line from before is read with no runs and is not handed in again.
   - A job name with a line break gives one line in the file.

## Why

`jobs` is empty while queued or running (Build.java), so the window names workflows only once a verdict is final, from
the jobs' `<workflow> / <job>` names.

## Acceptance

- Red first: a running build with two runs, one finished, reaches Tasks1 with both runs and their states.
- A reader that answers without `runs` still gives a build, with `runs` empty.
