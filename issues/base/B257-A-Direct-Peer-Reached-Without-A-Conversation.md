# B257 — A Direct Peer Reached Without A Conversation

```json
{
  "$schema": "issue-1.json",
  "id": "B257",
  "title": "A Direct Peer Reached Without A Conversation",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 80,
  "blockedBy": [],
  "covers": "A project whose peer has only a direct transport address reaches it, or its start is refused naming why, where today the send runs with no account and the transport refuses it"
}
```

**What must be true.** A project that names a peer only by a direct transport address (`matrix:<address>`), with no
`mail.transports` entry for that transport, either reaches the peer or is refused at its task's start, naming why. A
task's message to that peer is never held only once the transport refuses it.

## Why

Read in the code: such a project has no conversation (`Mail.conversations`), so a start runs no transport `setup`
and takes the task into no account (`TaskConversations.enroll`). Its send then has no account to act as
(`TransportConversations.acting` answers `null`), and `TransportSend` runs the transport with the peer's address and
no secrets. porelo-message-matrix's `send` requires a homeserver and an access token from its environment and exits 78
without them, so nothing is sent. The start does not say so; the message is held only once the transport refuses it.
`project inventory` is gone; transport installation by the host is not established by this checkout.
