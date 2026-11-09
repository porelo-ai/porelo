# [B528](README.md#b528) — A Person's Line To All Agents Wakes Every Task

**Status:** Review. Filed 2026-11-08 from the operator's decision on "All agents:" (move 3), with porelo-message-matrix marking such a line.
**Size:** S - the person file's `all` carried into the task's message and honored by the wake.

**What must be true.** A person's line in the room headed "All agents:", which porelo-message-matrix marks
`"all": true` in the person file, reaches every task of the project marked `metadata.all: true`, and wakes
each, whomever else it names. A task cannot wake them all: only a person's message (`ROLE_USER`) in the room counts.
A direct chat is with one task and carries no mark.

## As built

- `PersonsWords.read` puts `all: true` into the metadata of a room line the transport marked so. Readers are every
  task, as for every person's room line.
- `AgentWake.namesIt` wakes for it when the message is a person's (`ROLE_USER`) and `via` is `room`. A task's
  message with `all` wakes nobody it did not name.
- The guide (`MailboxGuide`, doc/messages.md) says such a message is meant for every agent.
- Tests: `PersonsWordsTest.aLineToAllAgentsIsMarkedSoForEveryTaskAndOnlyInTheRoom` and
  `AgentWakeTest.aPersonsLineToAllAgentsWakesEveryTaskAndATasksDoesNot`, both seen red.
