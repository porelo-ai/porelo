# B184 — Only What Porelo Delivered Counts As A Message

```json
{
  "$schema": "issue-1.json",
  "id": "B184",
  "title": "Only What Porelo Delivered Counts As A Message",
  "state": "Review",
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

## Built (2026-11-13)

- `Mailbox.delivered` takes a regular file, not followed, whose owner is the mailbox root's. `Mailbox.readDelivered`
  reads one through a hard link the host makes in `inbox/tmp`, which the agent cannot write, so a name replaced
  between check and read, a link and a pipe are never read; `link(2)` links a link itself, and the kernel refuses to
  link another account's file.
- The readers that read a message's bytes - `AgentWake`, `MessageVerify` and `TalkLogCommand` - read the inbox only
  through `readDelivered`; `TransportConversations.taken` and `TaskInventory.inboxWaiting` take only delivered names.
  `MessageVerify` reports an inbox file of a recorded name that Porelo did not deliver.
- `InboxPlants`, run after each pass of `MessageWatch`, records each name in `inbox/new` and `inbox/cur` that Porelo did
  not deliver as a `planted` line, once, and at most 100 per mailbox, so a process renaming one file without end
  cannot grow the record without end. `task status` shows what is in the inbox now and not Porelo's, looking at no
  more than 1000 entries, on an `inbox` line: the count in the warning colour, the names outside its markup, since
  whoever wrote them chose them.
- The guide and its copy in `doc/messages.md` tell the agent to read only root's regular files and to check the file
  it opened, not its name, with a one-line recipe over `/proc/self/fd` that opens no link and nothing but a regular
  file, so a planted pipe never holds the agent's read.
- `MessageVerify` looks for a recorded name in `inbox/new`, `inbox/cur` and `sent` and settles on the one Porelo
  delivered, so a file planted under the same name does not hide the real one; it looks once more when the agent
  moves the message while it looks. A delivered message that cannot be pinned (a full disk, no `inbox/tmp`) is
  reported as one that could not be read, never as changed.
- Tests: `MailboxTest` (delivered, a pin that leaves nothing, a link, a pipe, another account's file, the limit),
  `InboxPlantsTest` (once each, bounded), and one each in `AgentWakeTest`, `TransportConversationsTest` and
  `MessageVerifyTest` that a file of another account is not taken. Each was seen red with the owner check taken out,
  and the bound's test with the bound taken out. The count and the log's audience have no test of their own: a wrong
  count or audience only reads wrong.
- The code reading (Sonnet) found no red. Its yellows on the guide's recipe, the status line's markup, a planted
  name hiding a real one in verify and a pin failure read as a change are fixed, each with a test seen red first
  where it can do harm; the status line's markup has none, since it only reads wrong.
- A plan reading by Codex found three reds, all taken: the check-then-read race and a pipe (now the pin), the guide's
  check of a name (now the opened file), and a record growing per fresh name (now bounded).

Accepted, and why:

- **A renamed delivered message keeps its owner.** The agent can rename a delivered message to another arrived name
  in `inbox/cur`, or back into `new`: that marks the other read or wakes it once more. It changes nothing Porelo
  delivered and reaches no one beyond the task's own conversation.
- **A process that is root in the container is the account.** Under rootless podman, root inside is the account
  running Porelo, so a root process there can plant a file that counts as delivered. The agent cannot become root:
  the container runs with `no-new-privileges` and every capability dropped, so a setuid `sudo` gains nothing, and
  only Porelo itself enters as root (`podman exec --user root`). An image's own root daemon would count, and is the
  person's own choice of image.
- **The record names at most 100 planted files for good.** Its lines are chained and never pruned, so after 100 the
  record names no more; `task status` reads the inbox as it is now and still shows each one.
- **A machine whose podman maps the account to the agent's user** (`userns=keep-id` in the account's containers.conf)
  makes every file in the container the account's, and the check passes everything. Porelo sets no user namespace;
  that mapping is the person's own choice for their account.
- **Acceptance:** the scenario that plants a file with `podman exec --user` needs a VM; it runs in the stack's VM run.
