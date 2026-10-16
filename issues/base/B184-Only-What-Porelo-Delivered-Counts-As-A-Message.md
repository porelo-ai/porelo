# B184 — Only What Porelo Delivered Counts As A Message

**Status:** Estimated.
**Size:** M - an owner check where the host reads the inbox, links never followed there, the guide's rule for the
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

Read in today's code:

- The host stages a message in `inbox/tmp` (not writable by the agent), sets it `rw-r--r--` and renames it into
  `inbox/new` (`Mailbox.intoInbox`). Under rootless podman the account running Porelo is root in the container. The
  agent runs as a user of its own, mapped to a subordinate id.
- A rename keeps a file's owner, so a delivered message stays root's in `cur`. A file the agent's user writes is that
  user's. A planted file replacing a delivered one by name is the planter's.
- The host reads the inbox in three places:
  - `MessageLookup.messages` (a filter's answer and verdict);
  - `MessageVerify.messageIn`;
  - `TransportConversations.taken` (names only).
  The first two test with `Files::isRegularFile`, which follows a link.
- Outbox reading is already safe: it is not followed and takes regular files only (`MessageIntake`).

The plan:

- **The rule:** a message in the inbox is Porelo's exactly when its owner is the account running Porelo - root, seen from
  inside. The rule needs no key and no signature, and it holds for every agent: the agent's user cannot make a file
  root's.
- **The host:** `Mailbox.delivered(path)` checks that a path is a regular file, not followed, owned by the account.
  `MessageLookup`, `MessageVerify` and `TransportConversations.taken` take only those. A file that fails the check is
  not read. The next poll says once, in `task status` and the task's message log, that something not delivered by
  Porelo was found in the inbox, with its name.
- **The agent:**
  - The guide (`MailboxGuide`) says to read only messages owned by root, to leave others alone, and to report one if
    it is there.
  - The agent kit's reader, which agents with a Porelo plug-in use, applies the same check in code.
- **Not chosen:** a read-only inbox. Moving `new` to `cur` is how the agent and the host both know a message was
  read. Marking it read any other way would change the protocol for every agent, while the owner tells the same thing
  with nothing changed.

Steps:

1. **Red first:**
   - `MailboxTest`: a file of another owner, a link and a directory are each refused by `delivered`. This uses a
     temporary directory, and `Files.setOwner` to a uid the test can set, or a check against the file's actual owner.
   - `MessageLookupTest`: a planted link to a file outside is not read.
2. **Code:** `Mailbox.delivered`; the three readers; the note; the guide; the agent kit.
3. **Acceptance:** a stub task, `podman exec --user` as the agent writes a message into `inbox/new`. The host's lookup
   ignores it, and `task status` names it.
4. Reactor, documents and the suite on the VM (`not @restart`).
