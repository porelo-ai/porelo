# B203 — Task Names Checked By What The Commands Do

**Status:** Accepted.

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

**To be checked.** Whether every such command can run against a fake context without a daemon or a container, or
whether some need only their task resolution called.
