# [B523](README.md#b523) — A Message To People Reaches Persons Only

**Status:** Backlog. Filed 2026-11-08 from move 3's findings 13c and 22.
**Size:** S - the hand-out of a task's message and the mailbox card.

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
