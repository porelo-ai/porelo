# B304 — A Machine Sets Its Own Transport Settings

**Status:** Accepted.

**What must be true.** A machine that follows a project may set some of a transport's settings for itself - for
Matrix, the homeserver, the room, the CA file and the registration token file - in place of what the project file
names, so one project file serves machines that talk through different homeservers; every other setting stays the
project file's.

## Why

Decided for porelo-message-matrix
[MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42): one project file names
one homeserver, and a host's own homeserver is not the desktop's. Rather than every machine naming its homeserver as a
central one, a machine's settings may differ from the project file's.
Read in porelo at f4a0af45: a project's conversation comes from `mail.transports.<name>` in its project file, handed
to the transport as written (`doc/messages.md`), and a machine sets none of it.

## To be checked

- Where a machine's own settings live - the account's state, the machine file of `doc/hosts.md`, or a file beside the
  followed project - and who may write them, since the project file is signed and they are not.
- Which settings a machine may set: whether the transport declares them, so porelo names no Matrix setting, and how an
  `offline` project's loopback-only rule holds against a machine's homeserver.
- What `porelo-message-matrix` reads for a host's registration token file, agreed with its agent.
