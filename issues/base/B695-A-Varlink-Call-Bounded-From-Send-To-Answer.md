# B695 — A Varlink Call Bounded From Send To Answer

```json
{
  "$schema": "issue-1.json",
  "id": "B695",
  "title": "A Varlink Call Bounded From Send To Answer",
  "state": "Implementing",
  "size": "S",
  "priority": "Medium",
  "rank": 49,
  "blockedBy": [],
  "covers": "A varlink call's deadline covers its send as well as its answer, the 4 MiB cap is kept to the byte, and an agent's slow methods get a deadline that fits them.",
  "openQuestions": 0
}
```

**Why this size.** Three small changes in `wire` and `agents/api`, with a test each; no contract change.

Filed 2026-11-13 from a reading of [B200](README.md#b200), which left these open:

- **The send is unbounded.** `VarlinkClient.call` completes a blocking send before the deadline starts, so a peer
  that stops reading holds a large request for ever. The build reader and the ecosystem lost their whole-call
  bound with their executors.
- **The 4 MiB cap is not strict.** `VarlinkConnection` parses a terminated frame before checking its size, so a frame
  just over the cap that ends in the last read is accepted. This predates [B200](README.md#b200).
- **An agent's slow methods.** `ContainerSetup` and a batched `Format` get the 30 s default, and `InstalledAgent`
  has no way to give more; a legitimate slow answer fails and loses the connection.

**What must be true.** A call ends within its deadline counted from its first byte sent; a frame over 4 MiB is refused
whatever read ends it; `ContainerSetup` and `Format` wait as long as an agent's declared or a named longer bound.

## Plan

- `VarlinkConnection`: a bounded send (non-blocking with the selector for `OP_WRITE`, as the bounded read), the
  deadline shared with the answer; the size checked before a frame is parsed.
- `InstalledAgent`: a named deadline for `ContainerSetup` and `Format` (for example 120 s, as the build reader's
  question), passed to `call`.
- Red first: a peer that never reads a request larger than the socket buffer ends the call at its deadline; a frame of
  4 MiB plus one byte ending in the last read is refused; a stand-in agent answering `Format` after 31 s with a
  stretched clock is waited for.
- Coverage the reading named: blocking mode restored after a bounded read, a trickling peer past the whole deadline,
  the deadline reset between stream replies.

## Acceptance

- Each of the three holds, with a test that fails without it.
