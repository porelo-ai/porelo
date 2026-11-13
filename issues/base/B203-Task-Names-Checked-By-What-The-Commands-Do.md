# B203 — Task Names Checked By What The Commands Do

```json
{
  "$schema": "issue-1.json",
  "id": "B203",
  "title": "Task Names Checked By What The Commands Do",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 51,
  "blockedBy": [],
  "covers": "The test that every task and talk command takes both spellings of a task runs the commands through picocli's tree instead of matching their source text.",
  "openQuestions": 0
}
```

**Size.** M: one test across every `task` and `talk` command that takes a task, each run against a context, plus one
annotation on a command. Analyzed 2026-11-12 (ST56).

**What must be true.** Every `task` and `talk` command that takes a task finds it by its own name and by its
container's name. A test proves this by running the commands, not by reading their source.

**Why.** `porelo task files b37online` once said nothing was handed to the task, while the container's name listed six
records. Most task commands resolved both spellings through `TaskTarget`; `files`, `give`, `take-back`, `label`,
`clearance` and `talk` did not. For `give` and `take-back` that is more than a wrong answer: a hand-over meant for a task
goes nowhere.

The guard against it, `TaskNamesBothWaysTest` (`apps/app`), reads the source:
- it takes files named `Task*Command.java` or `Talk*Command.java`, with an exception list for one launched-only command;
- it decides by a regular expression that a command takes a task when its first `@Parameters` has `paramLabel = "<task>"`;
- it then only asks that the file mentions `TaskTarget.` somewhere.

On 2026-10-17 the shared code style moved `index = "0"` onto the line after `@Parameters(`, and the expression found 10
commands instead of more than 20. Only the test's floor of 20 noticed. Without that floor, half of the commands would
have stopped being checked, silently. A mention of `TaskTarget.` does not show that the command resolves its task
through it, either.

**Acceptance criteria.**
- [x] The test walks porelo's real picocli command tree (`CommandSpec`), not files. Every subcommand of `task` and
  `talk` whose first positional parameter is a task is found that way, with no list of file names.
- [x] Each such command is run against a context holding one task, once with the task's own name and once with its
  container's name. Both runs reach the same task; a command that refuses one spelling fails the test with its name.
- [x] A command a person never types (today `task watch-builds`, run by a task's launch) is left out by a property of
  the command itself, such as being hidden, not by its file's name.
- [x] The test does not depend on how the source is formatted. Reformatting a command's annotations changes nothing.
- [x] `TaskNamesBothWaysTest` is removed once the new test covers every command it checked.

## Plan

Read in the code on 2026-11-12. `TaskNamesBothWaysTest` (`apps/app`, 57 lines) matches `(Task|Talk)*Command.java` by
file name, skips `TaskWatchBuildsCommand.java` by name, and needs more than 20 hits. `TaskTarget.resolve` and
`resolveRead` (`TaskTarget.java:205`, `:256`) are the resolution. `task watch-builds` is not hidden today.
1. **Red first.** `TaskNamesBothWaysCommandsTest` builds porelo's real `CommandLine` from `PoreloFactory`. It walks the
   `task` and `talk` subcommands, takes each whose first positional parameter's `paramLabel` is `<task>`, and runs it
   against a context holding one stopped task, once by the task's own name and once by its container's name. A
   command that answers 66 (`TaskTarget.NONE`) for either spelling fails with its name. Seen red by turning one
   command's resolution back to the container name alone.
2. `TaskWatchBuildsCommand` gets `hidden = true` on its `@Command`, and the walk skips hidden commands.
3. A command that needs a running container or the daemon to answer is run with `FakeCommandRunner`. Where even that
   cannot reach its resolution, the test asserts on the found task through a seam the command already passes to
   `TaskTarget`, never on its source.
4. `TaskNamesBothWaysTest` is deleted once the new test's set holds every command the old one found.

## Open questions

- None: the acceptance criteria settle it.

## Built (2026-11-13)

`TaskNamesBothWaysCommandsTest` (`apps/app`) builds porelo's `CommandLine` through `PoreloCli.commandLine`, the one
`PoreloFactory` wiring the binary uses, walks every subcommand under `task` and `talk`, leaves out the hidden ones, and
takes each whose first positional parameter is labeled `<task>` or `TASK` (half the commands use one, half the
other). Each is run three times, each time in a fresh copy of one machine: one stopped task `birch` in project
`default`, container `porelo-default_birch`, with its ownership records, a profile, a mailbox and one hand-in record;
podman is a `FakeCommandRunner`. The runs are by the container's name, by the task's own name, and by a name no task
has. Where it departs from the plan:
- **No exit-66 rule.** A run by the own name must equal the run by the container's name in everything it does: exit,
  output, errors, the commands it ran and the files it left, with random ids and times evened out. That catches a
  command that passes the name on unresolved, which `TaskTarget.spelled` does without ever answering 66, and it does
  not fail `task certify`, which answers 66 for a reason of its own. The run by an unknown name must differ from the
  container's run, or the test fails saying it cannot tell which task the command reached.
- **Further parameters by label.** A required parameter after the task gets a value by its label (`<peer>`, `<id>`,
  `<mode>`, `FILE`, `NAME`, `CAPTION`); a new label fails the test until it is given one. A command with required
  options cannot be told apart from an unknown name, and fails the test the same way.
- **`task certify` named the name it was given** in its "has no key of its own" refusal, not the task found; it now
  names the container, as the other commands do. Its resolution was right.
- `task watch-builds` has `hidden = true`, and a second test holds that it is hidden and left out.

**The commands checked.** `task`: start, stop, refresh, remove, label, attach, status, logs, clearance, give,
take-back, files, certify. `talk`: pass, held, read, release, hold, verify, log, say, tell. That is 22. The old test
found these 22 and `TaskResumeCommand`, which no group registers: `porelo task resume` is no command, and a stopped
task comes back through `task start` and `task attach`. `TaskNamesBothWaysTest` is deleted.

**What was to be checked.** Every one of the 22 runs against the fake context as it is: no daemon, no container and no
seam to its resolution was needed. Each one resolves its task and answers about the one it found. Some stop soon after
the resolution, because the fake context has nothing more for them:
- `give` and `take-back` refuse a stopped task, and a running one is no help: they copy through `HandIns`' own process
  runner, not the context's.
- `attach` refuses a stopped task. `start` stops at the hooks not being installed, after naming the existing task.
- `certify` finds no key. `clearance` finds no process for a watcher. `remove` reads the task as running.

Seen red: with `task files` back on the container's name alone, the test failed naming it ("said 'nothing was handed
to birch'", where the container's name listed the hand-in). With `watch-builds` not hidden, both tests failed:
the walk took it in, and its required options left it unable to tell an unknown name apart.
