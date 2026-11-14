# B759 — A Transport Refusal Leaves The Other Projects' Pass Running

```json
{
  "$schema": "issue-1.json",
  "id": "B759",
  "title": "A Transport Refusal Leaves The Other Projects' Pass Running",
  "state": "Backlog",
  "priority": "Medium",
  "size": "S",
  "rank": 57,
  "blockedBy": [],
  "covers": "A fence that cannot be built for one project's verb (a name that does not resolve, an unreadable resolver file, a missing pasta) defers that project's messages and lets the pass go on for the others",
  "openQuestions": 0
}
```

**Severity:** medium - one project's DNS outage stops delivery and polling for every project in that pass; nothing
leaks, and nothing is lost: the queued messages stay queued.

**Why this size.** S: one place in each loop that runs a fenced verb, one exception type, and a test per loop.
Estimate: 38 minutes (Core's median for S).

**What must be true.** A verb that cannot be fenced is refused for its project alone; the pass goes on for the rest.

Found by the reading of [B743](README.md#b743) part (c) (agy, gemini-4-argon-high), checked in the code:

- `TransportFence.command()` throws `IllegalArgumentException` for a name that resolves to nothing or a resolver file
  it cannot use, `UncheckedIOException` for one it cannot read, and `CommandException` for a missing pasta, nftables
  or setpriv.
- `TransportSend.send()` runs the verb through `verbs.reaching(...).run(command)` and catches none of them; neither
  does the conversations pass (`TransportConversations`) around its per-project calls, nor `HelperAnswer`, which
  catches `CommandException` only. `TransportLifecycle` already turns both into a `Refused` for its own callers.
- So a transient lookup failure for one project's homeserver ends `send()` with an exception, and the pass that
  called it ends too: the other projects' messages wait for the next pass, which fails the same way while the name
  does not resolve.

## Plan

- `TransportFence.command()` throws one refusal type for every reason it cannot build the fence (a
  `CommandException` naming the reach or the program), so callers handle one type.
- `TransportSend.send()` defers that project's queued messages, as it does when its account cannot be had, and says
  why once; `TransportConversations` and `HelperAnswer` record the refusal for that project and go on.
- Red first: a pass over two projects, one whose saved reach does not resolve, delivers the other's message and
  defers the first's; the same for a pass whose pasta is missing.

## Acceptance

- In a pass over two projects where one project's reach does not resolve, the other project's message is delivered
  and the first's stays queued with the reason said once.

## Answered

- None open.

## Built

- **`TransportFence.command()`** refuses with `CommandException` for every reason it cannot build the fence. That
  covers an invalid or unreachable reach, a name that resolves to nothing, a resolver file it cannot read or use, and
  a missing pasta, nft or setpriv. The cause keeps today's sentence. `TransportFence.refusal(ex)` gives that sentence
  for any refusal, and `reachRefusal(ex)` gives it for a reach or resolver refusal only.
- **`TransportLifecycle`** turns a reach or resolver refusal into the same `Refused` ("the <scheme> transport could
  not run fenced: ...", 77) that it gave for `IllegalArgumentException` before. A missing program is rethrown as
  before, so start, members and room members say what they said. An unreadable resolver file used to escape as
  `UncheckedIOException` there; it is now that `Refused` too.
- **`TransportSend.send()`** defers that message and every later one of the pass when the fence refuses its verb,
  just as it does when its account cannot be had. `Result` has a new `waiting` field that gives the reason once, and
  `talk pass` prints it as `porelo: <transport> waits: <reason>`. Any other `CommandException` is still thrown.
- **`TransportConversations`** (`pass`, `waitOnce`, `waitDirect`, `confirm`) records a fence refusal as one failure
  of that project and goes on with the others. **`HelperAnswer`** gives the fence's reason instead of "could not be
  run".
- `TransportFenceTest` now expects `CommandException`, with the messages unchanged.

Seen red, then green (unit tests). The four new tests went red with `CommandException` escaping the pass when the
two catches were broken on purpose. They went green once the code was restored from a copy:

- `TransportSendTest`: `aPassOverTwoProjectsDeliversTheOthersMessageWhenOneProjectsReachDoesNotResolve` and
  `aPassWhosePastaIsMissingKeepsEveryProjectsMessagesWaitingAndGoesOn`.
- `TransportConversationsTest`: `aPassOverTwoProjectsHandsOutTheOthersArrivalWhenOneProjectsReachDoesNotResolve` and
  `aPassWhosePastaIsMissingRecordsEachProjectsRefusalAndGoesOn`.
- The reading (agy, gemini-4-argon-high) found no grave or normal gap. Two minor ones are closed: both send tests now
  check on disk that the refused project's messages left the active queue for the deferred one, and that each
  project says why it waits. Declined: the `IllegalArgumentException` catches left in `TransportLifecycle` no longer
  see a fence refusal, but other code on those paths may still throw one.
