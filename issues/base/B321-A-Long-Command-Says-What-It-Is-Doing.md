# B321 — A Long Command Says What It Is Doing

**Status:** Backlog, the operator's word on 2026-10-28: planned for a later round, after
[B318](README.md#b318).
**Size:** M - a line-by-line stderr path in the command runner, its redaction, the transport verbs and the other long
commands found by the sweep, docs and tests.

**What must be true.** A person who runs a command that takes long sees what it is doing while it runs, including what
a transport says about its own waits. A silent minute no longer looks like a hang, and no secret appears in what is
shown.

## Plan

Read in the code on 2026-10-29.

- **Today:** `TransportLifecycle.run` calls `context.runner().run(...)`, which collects stdout and stderr until the verb
  ends. stderr is shown only when the verb fails: cleaned with `StartStep.cleanLines` and redacted against the secrets
  handed in and those printed on stdout. `CommandRunner` has only `run(Command)`, with no way to see a line before the
  process ends. `porelo talk join` ran two minutes with nothing on the terminal while the transport's `setup` waited.
  A transport's progress lines on stderr, one every 10 s while it waits, reach nobody.
- **A task's start already shows its steps** (`StartSteps`). This issue does not change that path. It only gives
  the step line what the transport says while enrolling.
- **The steps:**
  1. `CommandRunner` gains a run that also hands each stderr line to a consumer as it arrives, and still collects
     the whole stderr for the result. `ProcessCommandRunner` reads stderr on a thread of its own, and
     `FakeCommandRunner` replays scripted lines.
  2. `TransportLifecycle.run` passes a consumer. It cleans each line, redacts it against the secrets handed in, and
     prints it on the person's stderr under the transport's scheme. In a task's start, the line becomes the
     conversation step's detail. On failure the message stays as today.
  3. A sweep of the command line for other commands that run long and print nothing until they end. Each one found
     gets a phase line as it starts. Candidates read so far: `project follow` (fetching the project's repository),
     `vault clear` and `project unfollow` (each runs a transport's `clear` or `part`), and `setup` (a transport's
     `setup`).
- **Red first:** a stand-in transport writes a line to stderr that holds a secret handed in, then waits. The test
  expects the line on the person's stderr before the verb ends, with the secret replaced by `[withheld]`. The harm it
  prevents is a secret shown on a terminal by the streamed path, which the failure path already guards.

## Acceptance

- `porelo talk join` against a transport that writes a progress line every 10 s shows each line as it comes, and
  stdout's answer is unchanged.
- A streamed line that holds a secret handed to the transport shows `[withheld]` in its place. The test is seen red
  with the redaction removed.
- Each command the sweep found prints a phase line before its long step.

## To be checked

- Whether a transport verb run by the daemon, from the interface, carries the streamed lines over varlink as a
  `more` reply, or only the command line shows them.
- A secret split across two stderr lines: redaction is per line, so the cleaning and the redaction must still catch
  it, or the issue must state why it cannot happen.
- The complete list from the sweep, with each command's long step measured on the VM.
