# B259 — A Person's Message Id The Filter Takes

**Status:** Backlog.
**Size:** XS - one id mapped where a person's words become a message, a test of the answer that copies it.
**Estimate:** 90 min, like porelo-message-matrix
[MX31](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx31) (46 min, one value mapped
before a check), plus porelo's whole chain with the VM.

**What must be true.** A person's message reaches a task with an id the message filter accepts, so a task that answers
it and names that id as its `contextId` is not bounced.

## Why

`PersonsWords` writes a person's message id as `person-` and the transport's event id as written. A Matrix event id
begins with `$`, and the filter's envelope check allows only `[A-Za-z0-9._:-]` in an id, refusing anything else as
`ENVELOPE/ID_MALFORMED`. Measured on the shared VM: a Claude task that answered a person and kept the person's id as
its `contextId` was bounced and needed a second turn. Every agent that answers a person this way loses one. The
transport hands the event id exactly as Matrix gives it, and keeps doing so; mapping it into the alphabet is porelo's.

## To be read before building

- Whether anything later maps the id back to the event, for a reply in a thread or a read receipt, and so needs the
  mapping to be reversible.
- Every other place a person's or a transport's id becomes a message id.
