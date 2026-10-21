# B244 — The Loop Watch Sees A Compact Screen

**Status:** Idea.

**What must be true.** A scenario that waits on a model stops as soon as the agent repeats a failed tool call, also
for an agent whose terminal redraws a compact view instead of scrolling each try into the history.

## Why

Measured in porelo-codex [CX01](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx01)'s live
wake case: the model called a tool with arguments the CLI refused (`bash: -c: option requires an argument`, twice,
then a bare `bash` with no output), and the kit's `LoopWatch` did not see it in Codex CLI's compact TUI; the step ran
to its 120-second bound and nine paid requests were spent. `LoopWatch` reads what scrolls into tmux's history, and a
view that redraws in place adds nothing there. The shared rule asks a test that waits on a model to fail fast on a
repeat; where the screen cannot show it, the agent's own record of its tool calls (its session file or run log) may.
