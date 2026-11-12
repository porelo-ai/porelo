# B632 — An Attended Task Takes Its Prompt As Its First Turn

```json
{
  "$schema": "issue-1.json",
  "id": "B632",
  "title": "An Attended Task Takes Its Prompt As Its First Turn",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "A Tasks1 Start with mode AGENT and a prompt answers once the task stands, and the agent's attended session, when it first starts, takes the prompt as its first turn and then waits for the person; an agent that cannot take one is refused with 64."
}
```

**Why this size.** S: one recorded opening text, read once where the attended session's command is built, one new
method on the agent API's definition, and the daemon's Start not running a prompt for an attended task.

Filed 2026-11-12 in `porelo-project` ST314, by the operator's decision of 2026-11-12 (option 1): the planner's idea
discussion is started from the window with mode AGENT and a prompt, and is attended: the person discusses in its
terminal.

**What must be true.** A Start with mode AGENT and a prompt does not run the agent: it answers once the container
stands, as a Start without a prompt does. The first time the task's attended session starts its agent, the agent is
started with the prompt as its first turn, and then waits for the person. A later session, or one continuing a
recorded session, is started without it. An agent that cannot be handed an opening text is refused with 64 before
anything is made.

How it is today, read in the code: Tasks1 Start's `prompt` makes the call run the agent to its end whatever the mode
(`TaskMethods`, `runAgent` then `promptEnded`), and the attended command (`AgentDefinition.attendedCommand`) carries
no text.

## As built

- `AgentDefinition.attendedCommand(model, opening)` puts the opening text after every flag, for an agent whose
  headless prompt is positional (pi). An agent with a prompt flag is refused: for claude and agy `-p` is print mode
  and says nothing of how a session takes an opening text (codex positional, agy `-i`, read by Agent Smith in the
  adapters); each declares its form in its own repository later. A text that is blank or begins with `-` or `@` is
  refused, since pi would read it as an option or a file.
- `TaskLaunch.opening()` is the prompt of a start whose mode is `AGENT`; the launch refuses with 64, before anything
  is made, when the agent cannot take it.
- porelod's Start keeps it with `TaskSession.open` in the task's state and runs nothing; the call answers once the
  task stands. `TaskSession.attachedAgent` hands it to the first fresh session only (`takeOpening`), never to a
  continued one or a second fresh one.
- Tasks1 Start's `prompt` says so; changelog entry.
- Tests: `HeadlessCommandTest` (positional opening last; a prompt flag refused), `TaskSessionTest` (the first fresh
  session takes it once), each seen red by a deliberate break.
- Not done here: the command line is unchanged, since `porelo task run` records any start with a prompt as
  UNATTENDED (`TaskRunCommand.mode()`), so `opening()` is never set there and `--attach agent --prompt` runs as before.
  No test drives a new attended task through porelod's Start end to end.

## After the code reading

agy (gemini) read the change. Taken, each with a test seen red where it did harm:

- **Red: an existing task.** Start with mode AGENT and a prompt for a task that exists answered 0 and kept a text its
  next session would drop or fail on. It is refused with 64 now, and nothing is kept:
  `PoreloDaemonTest.anExistingTaskStartedAttendedWithAPromptIsRefusedAndKeepsNoOpeningText`.
- **Usage before credentials.** The opening check runs with the other usage checks in `newCredentialsReady`, before any
  credential, host key or sign-in is asked for, and again before anything is made.
- **The text's shape.** A text holding NUL, or `-` or `@` after leading blanks, is refused too:
  `HeadlessCommandTest.refusesAnOpeningTextTheAgentWouldReadAsAnOptionOrAFile`.
- **The contract.** Tasks1 `Task.prompt` names an attended task's first turn; `minutes` and `maxTurns` say they bound a
  run only.

Not taken: the reading's red on the command line, which mistook the mode it passes (see As built). The opening
text lives in the runtime directory and a reboot before the first session loses it; the task then opens with no first
turn, which the person can give themselves.
