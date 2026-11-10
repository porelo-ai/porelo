# B527 — A Task's Message Reaches The Task It Names

```json
{
  "$schema": "issue-1.json",
  "id": "B527",
  "title": "A Task's Message Reaches The Task It Names",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "A task's message reaches only the task it names, in place of every agent reading every message."
}
```

**Why this size.** it replaces 2026-10-04's "every agent reads every message in the room" in the hand-out.

Filed 2026-11-08 from the operator's decision on move 3's 13c/22: "tasks reach each other only
by naming them".

**What must be true.** A task's message that arrives through the project's conversation is handed to the task its
`metadata.to` names and to no other task. A message to `people` reaches no task ([B523](README.md#b523)). The room
still shows every message to the persons.


## Why

In move 3 every message of one task woke the others, who filed them away as not meant for them, or answered.

## As built

- `TransportConversations.handOut` hands a task's signed message with any role except `ROLE_USER` to each local task
  of the project that its `metadata.to` names, and to no other task. One string or the string entries of a list
  name recipients; other list entries are ignored. A list containing no strings names nobody.
- A task counts as named by its container (`porelo-p-review`), its short name (`review`) or the display name its room
  holds for it ("Agent Review"). Without a recorded display, the derived task name applies only when no member holds
  it as a recorded display. Matching is exact and resolved per room; another project's records do not apply. Hand-out and wake use the same matcher and display resolution;
  `AgentWake.announce` reads recorded room names without asking the transport or opening the vault.
- A message naming nobody of this project here reaches no task, silently, before delivery checks: the room shows it
  to the persons. A message naming a task here goes through the existing delivery checks. A message to `people`
  reaches no task; a message never goes back to its posting account. Only a person's words reach every task in the
  room, and a direct chat reaches the one task it is with.
- An agent may write only the names its current peer card lists in `metadata.to`. Container and room display names
  also match on arrival; outbound dispatch still uses peer names. `MailboxGuide` and doc/messages.md explain both.
- `TalkPassProjectTest`'s removed-sibling tests name their recipients explicitly and prove that no message of one
  project reaches another project's task, with decoys. `EveryoneReadsTheRoomTest` uses a `ROLE_AGENT` fixture and a
  third, unaddressed task to prove recipient selection and sender exclusion.
- Regression tests cover exact container, short and display names, recorded display ownership, wake through
  `announce`, missing and unfamiliar roles, `people`, mixed recipient lists and silent unmatched delivery.
