# B119 — A Loop Seen In An Unattended Task's Log

**Status:** Analyzed.
**Size:** S - the acceptance kit alone: one step, one more source for `LoopWatch`, and their unit tests.

**What must be true.** A wait in the acceptance kit stops early when an unattended task's agent repeats a failing
tool call that its screen does not show, read from the task's `task.log` against a marker the scenario declares.

## Why

The kit's waits stop on a loop by counting what scrolls into each task's pane history: a declared tool-failure
marker three times since the step began, or one line more often than `porelo.acceptance.repeats` allows. That sees
omp, which draws every failed call, and Pi, whose failures scroll. It does not see Claude Code: measured on 2.1.267,
two failed calls collapsed into one line updated in place, so nothing new scrolls and a loop within one turn runs to
the step's time limit. An unattended Claude writes each tool result to `task.log` as JSON, where a failed one carries
`"is_error":true` and can be counted.

An attached session writes no `task.log`, so this does not cover it. There, the step's time limit stays the bound.

## Plan

Read in the code at `30c8169b`, all in `acceptance/kit/src/main/java/org/fuin/porelo/acceptance/`:

- `TaskHistories` (a `Terminal.Loops`) runs `LoopWatch.COMMAND` on the machine - one shell line printing each task's
  pane history after a separator - and hands it to `LoopWatch.check`, which keeps the first look of a step as its
  baseline and counts what was added since. `Repeated.untilZero` calls `reset` and `check` the same way for script
  waits. The screen's marker is declared by `TerminalSteps.aToolFailureLooksLike` into `World.toolFailure`.
- **Read from the state directory directly**, as the kit's other reads of a task's state are: `task.log` is at
  `$XDG_RUNTIME_DIR/porelo/<container>/task.log` (`TaskPaths.containerState`). `porelo task logs` would cost a `porelo`
  start per task per look and adds nothing. The command prints, per container, only the count of lines holding the
  marker (`grep -cF`), so a long log is not copied over ssh at every look.
- `World` gains `logFailure`, set by a new step `a tool failure in the task's log looks like {string}` in
  `TerminalSteps`. `LoopWatch` takes a second marker and a second set of counts: the first look is the baseline, a
  container without a log counts zero, and `MARKER_REPEATS` added since the step began stops the wait, naming the
  task and the marker. `TaskHistories.check` runs both commands in one `world.run`.

Red first: `LoopWatchTest` - three failed results added to a stand-in log after the baseline leave `check` empty.
Then a baseline with failures already in it counts nothing, and a container with no log counts nothing.

## Acceptance

- A scenario declares what a failed tool call looks like in a task's log, apart from the screen's marker:
  `Given a tool failure in the task's log looks like "\"is_error\":true"`.
- While a terminal or script wait runs, the kit reads the `task.log` of every task the account runs, and stops once
  the declared log marker has been added three times since the step began, naming the task and the line. Seen to
  fail: a fake log gaining three failed results leaves the wait running to its end.
- A log that does not exist, as for an attached session, and lines that were there before the step, count nothing.
  Seen to fail: a wait stopping on failures logged in an earlier step.
