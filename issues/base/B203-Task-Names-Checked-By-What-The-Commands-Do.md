# B203 — Task Names Checked By What The Commands Do

```json
{
  "$schema": "issue-1.json",
  "id": "B203",
  "title": "Task Names Checked By What The Commands Do",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 45,
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
- [ ] The test walks porelo's real picocli command tree (`CommandSpec`), not files. Every subcommand of `task` and
  `talk` whose first positional parameter is a task is found that way, with no list of file names.
- [ ] Each such command is run against a context holding one task, once with the task's own name and once with its
  container's name. Both runs reach the same task; a command that refuses one spelling fails the test with its name.
- [ ] A command a person never types (today `task watch-builds`, run by a task's launch) is left out by a property of
  the command itself, such as being hidden, not by its file's name.
- [ ] The test does not depend on how the source is formatted. Reformatting a command's annotations changes nothing.
- [ ] `TaskNamesBothWaysTest` is removed once the new test covers every command it checked.

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

**To be checked.** Whether every such command can run against a fake context without a daemon or a container, or
whether some need only their task resolution called.
