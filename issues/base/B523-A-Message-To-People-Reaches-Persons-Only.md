# B523 — A Message To People Reaches Persons Only

```json
{
  "$schema": "issue-1.json",
  "id": "B523",
  "title": "A Message To People Reaches Persons Only",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 52,
  "blockedBy": [],
  "covers": "A task's message to `people` reaches the persons only, no other task; the card names the room and the admitted persons."
}
```

**Why this size.** the hand-out of a task's message and the mailbox card.

Filed 2026-11-08 from move 3's findings 13c and 22.

**What must be true.** A task's message with `metadata.to` `people` goes into the room for the persons and is handed
to no other task's inbox. A message naming another task still reaches that task, and the room sees it. The card
(`peers.json`) names the room the task speaks in and each admitted person by the name a message reaches them with, so
an agent knows whom `people` is before it writes.

## Why

`TransportConversations` hands a task's message to every task of the project but the poster, whatever it names
(the `readers` of a task's message), so in move 3 each agent's answer to the persons woke every other agent, who
answered in turn. The card says only "the people in your project's conversation".

## Acceptance

- Red first: a task's message to `people` is handed to no other task; one to `porelo-x-pi` reaches that task.
- The card lists the room and each admitted person; the guide says a message to `people` reaches no task.

## As built

- `TransportConversations.handOut` takes a task's message whose `metadata.to` is `people` off the inbound without
  handing it to any task: the room already has it. It is not counted as dropped. A task's message to a task still
  reaches every other task but its sender, as before.
- The guide (`MailboxGuide`, doc/messages.md) says a message to `people` reaches no task. The card's `people` entry
  reads "the persons in your project's room; no other task reads it". The persons it lists one by one are
  `Conversations.members`: the joins and the admitted persons taken by their host account. On a host's own
  homeserver that is [B519](README.md#b519)'s admitted logins.
- Test: `EveryoneReadsTheRoomTest.aTasksMessageToPeopleReachesNoTaskAndOneToATaskReachesTheOthersButNotItsSender`.
  It replaces the test that pinned the old rule and was seen red against it.
- Not built: the room's own name on the card. The card is written from the project file, which names the transport
  but not the room.
- After its reading (agy gemini-4-argon-high, read-only, on a9eba2ce5; each finding checked in the code):
  - `people` is recognized in every form `MessageDispatch` sends: a list of one beside blanks, or with spaces.
    Before, `["people"]` still reached every task.
  - Only in a task's own signed message (`ROLE_AGENT`, with its signature). A person's words to `people`, through
    `talk say`, reach the tasks as a person's do. An unsigned message is handed on to be held, not taken away.
  - Left to [B527](README.md#b527): a task's room answer to a person by name still reaches every other task.
  - Test: `EveryoneReadsTheRoomTest.peopleIsKnownInEveryFormADispatchTakesAndOnlyInATasksOwnSignedMessage`, seen
    red before the fix.

