# B244 — The Loop Watch Sees A Compact Screen

**Status:** Backlog.
**Size:** M - one module, the acceptance kit, with a published step agent repositories use; L if the file's place comes from the agent definition instead.

**What must be true.** A scenario that waits on a model stops as soon as the agent repeats a failed tool call, also
for an agent whose terminal redraws a compact view instead of scrolling each try into the history.

## Why

Measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s live
wake case: the model called a tool with arguments the CLI refused (`bash: -c: option requires an argument`, twice,
then a bare `bash` with no output), and the kit's `LoopWatch` did not see it in Codex CLI's compact TUI; the step ran
to its 120-second bound and nine paid requests were spent. `LoopWatch` reads what scrolls into tmux's history, and a
view that redraws in place adds nothing there. The shared rule asks a test that waits on a model to fail fast on a
repeat; where the screen cannot show it, the agent's own record of its tool calls (its session file or run log) may.

## Plan

Read in `LoopWatch`, `TaskHistories`, `SessionIds`, `TaskSession.fromFiles` and porelo-codex's `codex.yaml`.

1. `LoopWatch` reads `tmux capture-pane` history only, so a view redrawn in place adds nothing; it stops at three
   marked failures, and the measured case had two before a bare call. Codex CLI's definition declares no session
   directory or suffix, so nothing declared says where its tool calls are kept.
2. **A new kit step** says where the agent records its tool calls (a directory under its home and a suffix), checked
   by the same plain-path rule as `SessionIds`; `LoopWatch` also reads the newest such file in each task container
   and counts the marker in what was added; `TaskHistories` takes both.
3. **Red first:** `LoopWatchTest.aToolFailureThatTheSessionFileRecordedThreeTimesIsALoopWhileTheHistoryAddsNothing`.

**Decided:**
- The file's place comes from a kit step, in the kit only; the agent definition is not changed here.
- The threshold stays three, and an empty call right after a refused one counts as a repeat.

## Acceptance

- In porelo-codex's live wake case, a model repeating a refused tool call stops the scenario well before its bound.
  Seen to fail first: today it runs to the 120 s bound.
- A kit test with an unchanged history and a growing session file reports a loop.

## To be checked

- Where Codex CLI keeps its attended session files, and whether the marker survives their JSON escaping.
