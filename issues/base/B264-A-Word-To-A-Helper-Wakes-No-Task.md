# B264 — A Word To A Helper Wakes No Task

**Status:** Accepted; blocked by porelo-message-matrix
[MX35](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx35). Built only if the helpers
must stay visible; if that issue leaves no helper in view, or none needed at all, this one is closed.

**What must be true.** A person's message in a project's room that names only an account that is neither a task nor
a person, such as the room's administrator or its relay, wakes no task, and the person is told that nobody here
reads such a message. A message that names nobody at all still reaches every agent in the room, as today.

## Why

Seen in the operator's test on a rented host on 2026-10-23: the room's members included `porelo`, the homeserver's
administrator, and `porelo-relay-sprint2`, the account the transport reads the room with. The operator mentioned
`porelo` to ask who it was, and a task answered instead.

Read in the code: `PersonsWords.read` keeps a mentioned account in the message's `to` only when it is a task's
account or a person who joined, so a mention of a helper leaves `to` empty. A person's word to the room with an
empty `to` is meant for every agent there. A mention of the relay never reaches porelo at all, since the Matrix
transport strikes its own poller from `to` before it hands the message over. So porelo cannot tell today "names only
a helper" from "names nobody".

The Matrix transport's half is [MX35](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx35):
its goal is no helper account in view, or none needed at all.

## To be decided in the analysis

- How the transport tells porelo that a message named only helpers. It could keep them in a field of their own
  rather than dropping them, or mark the message, so porelo can tell it from one that names nobody.
- What the person is told, and where: in the room by the relay, or nowhere but the task's own record.
