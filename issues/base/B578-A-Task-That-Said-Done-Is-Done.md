# B578 — A Task That Said Done Is Done

```json
{
  "$schema": "issue-1.json",
  "id": "B578",
  "title": "A Task That Said Done Is Done",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 19,
  "blockedBy": [],
  "covers": "A task whose agent said done and stopped has the state `done`."
}
```

**Why this size.** a task state from the agent's own word, kept across a stop, in the contract and the list.

Filed 2026-11-09 under ST290, from the fourth move.

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

## As built

The task endpoint can carry `Done(task)`, a writing method held to the socket's own task. It refuses
even a child and accepts no completion text. Porelod writes the fixed completion value into the
host's private durable task record, outside the mailbox mount. Stops and reboots keep it; a later
start clears it. The room message remains part of the agent's instructions.

The inventory reports state `done` and activity `DONE` for a recorded task that is stopped or whose
existing observed activity is `IDLE`. New output reports `WORKING` while keeping the declaration;
`WAITING`, `UNKNOWN` and `ENDED` keep their meanings. The completion record adds no waiting reason.
CLI list, status and daemon list use this inventory. Test execution and environment limits are in
the worktree's untracked build report.
