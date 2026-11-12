# B632 — An Attended Task Takes Its Prompt As Its First Turn

```json
{
  "$schema": "issue-1.json",
  "id": "B632",
  "title": "An Attended Task Takes Its Prompt As Its First Turn",
  "state": "Implementing",
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
