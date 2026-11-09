# [B578](README.md#b578) — A Task That Said Done Is Done

**Status:** Backlog. Filed 2026-11-09 under ST290, from the fourth move.
**Size:** M - a task state from the agent's own word, kept across a stop, in the contract and the list.

**What must be true.** A task whose agent said it is done and stopped has a state of its own, `done`, in the list,
`task status` and over the daemon, and is no longer shown as running or waiting on the person.

## Plan

Read in the code: a task's state comes from its container and the activity read from its terminal (`TaskInventory`
`activityOf`, `waitingFor`); nothing records the agent's own word that the work is done.

- The task's MCP tools gain a way to say done (the room message the first words ask for stays); porelod records it in
  the task's record beside its mailbox, which outlives a stop.
- `TaskInventory` reports `done` for a task so recorded that is stopped or at rest; a later start clears it.
- Tasks1's state values, its interface text, `doc/` and a changelog entry.
- Red first: a task that recorded done and stopped lists as `done`, not `exited`/running.
