# B695 — A Varlink Call Bounded From Send To Answer

```json
{
  "$schema": "issue-1.json",
  "id": "B695",
  "title": "A Varlink Call Bounded From Send To Answer",
  "state": "Review",
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

## Built

- **Bounded send.** `VarlinkConnection.send(Map, Duration)` sends without blocking. It tries one write first, then
  waits on a selector for the socket to take more, until the deadline. Past the deadline it throws
  `SocketTimeoutException`, and the channel goes back to blocking mode afterwards. The blocking `send(Map)` stays for
  the server, for a call that waits for a person, and for unbounded streams.
- **One deadline from send to answer.** `VarlinkClient.call(method, params, deadline)` sends within the deadline and
  reads its answer within what is left of it. A send that times out closes the connection, as a read that times out
  already did, and throws `VarlinkTimeoutException` naming the whole deadline. For `callMore` with `eachReply`, the
  send and the first reply share one bound, and each later reply has its own bound, counted from the reply before.
- **The 4 MiB cap to the byte.** `VarlinkConnection.buffered()` refuses a frame whose NUL lies past `MAX_MESSAGE`
  before parsing it. A frame of exactly 4 MiB is still read.
- **Slow agent methods.** `InstalledAgent.SLOW_DEADLINE` is 120 s, the build reader's bound. `containerSetup` and the
  batched `format` use it. An agent's own declared bound is not built, as the Plan says.
- **Tests.** `VarlinkDeadlineTest` is new, with six cases:
  - a peer that never reads a large request
  - 4 MiB plus one byte, refused
  - exactly 4 MiB, read
  - a trickling peer
  - the deadline reset between stream replies
  - blocking mode restored after each case

  `InstalledAgentTest` has two new tests: an agent answering `format` or `containerSetup` after 31 s is waited for.
  The 31 s are simulated in the test client; there is no clock in wire.

  The new tests failed on the build box before the change and pass after it:
  - red: `buildbox-porelo-a002d3d505d4-20261113T171021Z.log` (wire) and
    `buildbox-porelo-8fd41d2d57c5-20261113T171104Z.log` (agents/api)
  - green: `buildbox-porelo-1e425fab71a4-20261113T171410Z.log`, run with `-Pwhole` over wire, agents/api, builds/api
    and ecosystems/api, 0 skipped
