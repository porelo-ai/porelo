# B321 — A Long Command Says What It Is Doing

**Status:** Implementing.
**Size:** M - a line-by-line stderr path in the command runner, its redaction, the transport verbs and the other long
commands found by the sweep, docs and tests.

**What must be true.** A person who runs a command that takes long sees what it is doing while it runs, including what
a transport says about its own waits. A silent minute no longer looks like a hang, and no secret appears in what is
shown.

## Plan

Read in the code on 2026-11-04.

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
     gets a phase line as it starts. Candidates: `org fetch` (fetching the organization's repository),
     `vault clear` and `project remove` (transport cleanup), and `setup` (a transport's `setup`).
     `project follow` and `project unfollow` are gone.
- **Red first:** a stand-in transport writes a line to stderr that holds a secret handed in, then waits. The test
  expects the line on the person's stderr before the verb ends, with the secret replaced by `[withheld]`. The harm it
  prevents is a secret shown on a terminal by the streamed path, which the failure path already guards.

## As built

- `CommandRunner.run(command, lines)` hands over each line of standard error. A runner of no lines of its own, a test's,
  hands them over at the end. A line longer than 16 KiB is shown as `[a line too long to show]`, never in pieces.
- A transport's line reaches a person at a terminal, or the conversation step's `waitingOn` in a task's start. Off a terminal
  and outside a start it goes nowhere: the daemon's journal gets none.
- Phase lines, on standard error, so an answer in `--json` is unchanged: `org fetch`, `setup --organization`, `vault clear`,
  `project remove`. The sweep went by reading; `talk join` and the rest show the transport's own lines through the lifecycle.

Answered from *To be checked*, by reading:

- **Over varlink:** no `more` reply. An interface sees the lines only as a start's step, through `waitingOn`.
- **A secret across two lines:** a secret of several lines is withheld a line at a time. One written in two pieces by the
  transport, or one it makes while it runs, is not known and not caught, so a transport must not say one on standard error:
  `doc/transports.md` says so.

Not measured: the sweep's long steps on the VM.

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
