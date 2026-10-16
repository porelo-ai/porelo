# B122 — No Direct Chat Between Tasks

**Status:** Analyzed.
**Size:** M - `app-messaging` and the start's check in `app-task`, the mailbox guide and `doc/messages.md`.

**What must be true.** Two tasks never talk in a direct chat: a direct chat exists only between a
task and a person who joined the project's conversation, so what tasks say to each other is said
where the operator reads along.

## Why

A conversation server may encrypt end to end, and then whoever is not in a chat cannot read it. The
operator is in the project's conversation, so what tasks say and hand each other there stays visible
to them; a chat between two tasks would not be.

Within a project this already holds: every task of the project is reached through its conversation,
and a direct chat is used only to answer a person who wrote directly. The gap is a peer declared in
the project file with an address of an account, `<transport>:@account`, which `porelo talk peers`
shows as "a direct chat with" it. Nothing checks that the account is a person, so a task of another
installation declared that way, and a message with `"via": "direct"`, makes a chat between two tasks
the operator is not in. The default `others: deny` holds it back until a project allows it; nothing
forbids it.

## Plan

Read in the code at `30c8169b`:

- **Where the gap is.** `ProjectReader` (`core/.../project/ProjectReader.java`) reads a peer's address as
  `<transport>:<rest>` and leaves the rest to the transport (`Mail.Peer`). `MessageDispatch.dispatch`
  (`apps/app-messaging/.../MessageDispatch.java`) writes the peer's destination beside a queued message.
  `TransportSend` takes any destination starting with `@` as a person, and sends it into a direct chat when the
  message says `"via": "direct"`. Neither asks whether the account is one of the persons in
  `Conversations.members(context, project)` (person to account, kept by `TaskConversations`).
- **The core cannot refuse it alone**: whether an account is a person of the conversation is known on the host, not
  in the file. So the check is in `app-messaging`: a peer whose address names an account (`@` after the colon, as
  `TransportSend` already reads it) is accepted only when that account is a value of `members`.
- **Refused where the file is read for work**: `StartCheck` (`apps/app-task/.../StartCheck.java`), which reads the
  project file for every start, and `TalkPeersCommand.listed`, which shows the peer, name the peer and why. A task
  already running meets the same check at its next dispatch.
- **Refused where a message leaves the task**: `MessageDispatch.dispatch` holds a message with `"via": "direct"` whose
  peer is not a person who joined, with the reason, before a destination is written; `TransportSend` checks again
  that a direct destination is a member's account, so a destination queued before the change never reaches a direct
  chat.

Steps: the check beside `Conversations.members`; `MessageDispatch` and `TransportSend` use it; `StartCheck` and
`TalkPeersCommand` refuse; `MailboxGuide` and `doc/messages.md` say a direct chat is only with a person, and why.

Red first: `TransportSendTest` - a message with `"via": "direct"` queued for another task's account is handed to the
stand-in transport with `--to` that account.

## Acceptance

- A peer whose address names an account is accepted only for a person who joined the project's
  conversation, and a project file that names any other account as a peer is refused when it is
  read, naming the peer and why. Seen to fail: a project file with a peer at an account that is no
  person of the conversation is read without a refusal.
- A message with `"via": "direct"` to anything but such a person is refused where it leaves the
  task, naming why, never sent into a direct chat. Seen to fail: a unit test sending one to another
  task's account finds it handed to the transport.
- A task still answers a person in their direct chat, and a person still writes to one task
  directly. Seen to fail: the existing direct-chat scenarios.
- The mailbox guide and `doc/messages.md` say that a direct chat is only ever with a person, and
  why.

## To be checked

- Whether a project file naming a person's account who has not joined the conversation yet is refused at the
  start, or its peer only held until they join (Agent Core's).
