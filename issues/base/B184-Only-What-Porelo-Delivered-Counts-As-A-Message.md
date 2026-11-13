# B184 — Only What Porelo Delivered Counts As A Message

```json
{
  "$schema": "issue-1.json",
  "id": "B184",
  "title": "Only What Porelo Delivered Counts As A Message",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "A message in the inbox counts only when the account running Porelo owns it (root inside); the host reads the inbox without following links and names a planted file; the guide tells the agent to check the owner."
}
```

**Why this size.** an owner check where the host reads the inbox, links never followed there, the guide's rule for the
agent, the same check in the agent kit, unit tests and an acceptance scenario that plants a message from inside.

**What must be true.** A file that a process in the container puts into its own inbox is never taken for a message
Porelo delivered: not by the host, and not by an agent that follows its guide. A link in the inbox is never followed by
the host.

## Why

`inbox/new` and `inbox/cur` are writable from inside the container (0777, `Mailbox.permissionsOf`). That is on
purpose: the agent is another user there, and it moves a message it has read from `new` to `cur`. But then any
process in the container - a dependency's install script, say - can drop a file into `inbox/new` that looks like a
person's or a peer's message, and the agent may act on it. Found in a security analysis run by an agent inside a task.

## Plan

Read in the code on main 177f3cae2:

- The host stages a message in `inbox/tmp` (not writable by the agent), sets it `rw-r--r--` and renames it into
  `inbox/new` (`Mailbox.intoInbox`, from `MessageDelivery`, `BounceDelivery` and `PersonNote`). Under rootless podman
  the account running Porelo is root in the container; the agent is a user of its own, mapped to a subordinate id. A
  rename keeps a file's owner, so a delivered message stays the account's in `cur`, and a file the agent's user writes
  or puts in place of a delivered one is that user's.
- Five host readers take what is in `inbox/new` or `inbox/cur` without asking who wrote it:
  - `AgentWake.announceHeld` wakes the agent for a `.json` in `inbox/new` that names it.
  - `TaskInventory.inboxWaiting` counts them (`inboxWaiting` on the wire), following links (`Files::isRegularFile`).
  - `MessageVerify.messageIn` checks a recorded signature against the inbox's file of that name.
  - `TransportConversations.taken` tells a transport a conversation's message was read when a file of that name is in
    `inbox/cur`.
  - `TalkLogCommand` reads a recorded message's audience from the first directory holding a file of that name, the
    inbox first.
- `MessageLookup` reads `feedback/` and `record/answers/`, not the inbox, and the outbox is already read without
  following links and as regular files only (`MessageIntake.taskWritten`); neither changes.
- No agent kit in this repository reads the inbox: the plug-ins' repositories hold only acceptance features that
  write into it. The guide is the agent's whole rule.

The design:

- **The rule:** a file in the inbox is Porelo's exactly when it is a regular file, not followed, whose owner is the
  owner of the mailbox's root - the account running Porelo, root seen from inside. It needs no key, and the agent's user
  cannot make a file the account's.
- **`Mailbox.delivered(Path)`** in app-base applies it with `NOFOLLOW_LINKS`; a file that cannot be read for its owner
  is not delivered. It sits beside the methods Core's work adds to `Mailbox` and changes none of them.
- **The five readers** take only what `delivered` accepts. `MessageVerify` reports an inbox file of a recorded name
  that is not delivered as a fault: "it was not delivered by Porelo".
- **The note:** each pass of `MessageWatch` over a mailbox lists `inbox/new` and `inbox/cur` (`InboxPlants`, a class of
  its own in app-messaging) and appends one `planted` line, direction `in`, to the message record for each name that
  is there, not delivered, and not yet recorded so. `task status` shows them on an `inbox` line, in the warning colour,
  read from that record; nothing on the wire changes.
- **The guide** (`MailboxGuide`) says to read only messages the root user owns, to leave any other file alone, and
  that the host names it in `task status`.
- **Not chosen:** a read-only inbox. Moving `new` to `cur` is how the agent and the host both know a message was read,
  and the owner tells the same with no change to that protocol.

Steps and tests:

1. **Red first, unit tests over temporary directories:** `MailboxTest` - a file owned by another account (the check
   given an account the file does not belong to), a link to a delivered file and a directory are each not delivered,
   and a file of the account is. `InboxPlantsTest` - a planted file and a link in `inbox/new`, and one in `inbox/cur`,
   are each recorded once over two passes, and a delivered message is not. One test per reader that a planted file is
   not taken: not woken for, not counted, not marked read, not read for an audience, reported by verify.
2. **Code:** `Mailbox.delivered`, the five readers, `InboxPlants` and its call in the pass, the `task status` line,
   the guide.
3. **Acceptance:** a stub task in which `podman exec --user` as the agent writes a message into `inbox/new`; the host
   does not wake for it and `task status` names it. It needs a VM and runs in the stack's run on the VM.
4. The tests of app-base and app-messaging and the documents; the whole reactor once before the hand-over, one
   `apps/app` run at a time on this machine.
