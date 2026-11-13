# B179 — A Code Beside Why A Release Cannot Go

```json
{
  "$schema": "issue-1.json",
  "id": "B179",
  "title": "A Code Beside Why A Release Cannot Go",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 38,
  "blockedBy": [],
  "covers": "A code beside `detail` when a release is answered `NOT_DELIVERABLE`, so a client can say what to do per case.",
  "openQuestions": 0
}
```

**Why this size.** small in code, one module's six returns and one reply, but a contract the interface reads, with a new
type and two cases no test covers yet.

**What must be true.** When a person's release of a held or refused message is answered `NOT_DELIVERABLE`, the reply
of `Release` in `45-messaging.varlink` carries a code beside its `detail` sentence - `NOT_IN_CONVERSATION`,
`REFUSED_BEFORE`, `UNCHECKED`, `NO_ADDRESSEE`, `PEER_NOT_ALLOWED`, `SIGNATURE_MISSING` - so a client can say what to do
for each case without matching on an English sentence that is no contract. The field is added, so a client that does
not read it sees no change.

Asked for by the interface,
which shows the sentence as it is until then.

## Plan

Read in the code on 2026-10-20 (`apps/app-messaging`, `daemon`).

1. **The six cases are exactly the six proposed**, each one `return` in `MessageRelease`:
   - `NOT_IN_CONVERSATION`: a held message from a task outside the transport's conversation (`decideHeld`, `:202`,
     sentence from `outside()`, `:337`);
   - `REFUSED_BEFORE`: a person refused it for good (`:212`);
   - `UNCHECKED`: the filter could not check it (`:217`);
   - `NO_ADDRESSEE` and `PEER_NOT_ALLOWED`: one `new Result` behind a ternary in `despite()` (`:255`), split in two;
   - `SIGNATURE_MISSING`: `despite()`, `:267`.
2. **`MessageRelease.Result`** (`:129`) gains `@Nullable NotDeliverable code`, a Java enum of the six, set on every
   `NOT_DELIVERABLE` return and only there; the sentences stay as they are.
3. **The contract**: `type NotDeliverable (NOT_IN_CONVERSATION, REFUSED_BEFORE, UNCHECKED, NO_ADDRESSEE,
   PEER_NOT_ALLOWED, SIGNATURE_MISSING)` in `45-messaging.varlink`, each value with what a person does about it, and
   "new values may appear; render an unrecognised one", as `StartOutcome` (`15-task.varlink:626`) says it; `Release`
   gains `code: ?NotDeliverable`, absent unless `NOT_DELIVERABLE`. `MessagingMethods` (`:233`) puts it in the reply.
   The comment on `detail` is corrected: it is also set on `RELEASED` for a message held on its way in
   (`MessageRelease.INWARD`), which carries no code.
4. `porelo talk release` keeps printing the sentence; it gains nothing, since a person reads the sentence.

**Red first:** `MessageReleaseTest`: each of the six cases answers its own code, `NO_ADDRESSEE` and
`PEER_NOT_ALLOWED` with a test each, which none has today; a daemon test calls `Release` and asserts its reply's
keys, as `PoreloDaemonTest` does for `ImportCredential` (`:2890`) - no daemon test calls `Release` today.

## Acceptance

- Every `NOT_DELIVERABLE` reply carries one of the six codes, and no other outcome carries one.
- A client that does not read `code` sees no change.

## Decided by the operator (2026-11-12)

- To Backlog, priority unchanged.

## Done

Built as planned.

- **The code.** `MessageRelease.NotDeliverable` is an enum of the six cases. `MessageRelease.Result` gains `code`,
  set on each of the six `NOT_DELIVERABLE` returns and on no other; the sentences are unchanged. The ternary in
  `despite()` is now two returns, `NO_ADDRESSEE` and `PEER_NOT_ALLOWED`.
- **The contract.** `45-messaging.varlink` gains `type NotDeliverable`. Each value says what a person does about it,
  and the type says a client renders a value it does not know. `Release` answers `code: ?NotDeliverable`, absent for
  every other outcome, so a client that does not read it sees the reply as before. The comment on `detail` now also
  names the message held on its way in that `RELEASED` gives the task, and says the sentence is no contract.
  `MessagingMethods` puts the code in the reply only when it is set.
- **`porelo talk release`** prints the sentence as before.
- **Red first.** The type and an always-null `code` came first. The six cases in `MessageReleaseTest` failed then,
  `NO_ADDRESSEE` and `PEER_NOT_ALLOWED` each with a test of its own, as did `PoreloDaemonTest`'s call of `Release`,
  which asserts the reply's keys with and without a code. The test that no other outcome carries a code passed
  from the start, as it should.
- **Locally on this commit.** `-pl apps/app,daemon -am install -DskipTests` passed, and so did the targeted tests:
  `MessageReleaseTest`, `MessageReadTest`, `TalkReleaseOutputTest`, the new `PoreloDaemonTest` case, and wire's
  contract and varlink tests. So did the root's `validate` and `-Pdocuments test`. The whole `apps/app` and `daemon`
  run did not finish: it was stopped while the machine was low on memory, and the coordinator chose to commit on the
  targeted tests.
- **The two lists tied.** The six names are written twice, in the contract's `NotDeliverable` and in the Java enum.
  `MessageReleaseTest.the_codes_are_the_contracts_own` reads the contract from the class path and holds that both
  name the same values in the same order. A seventh value added to the Java enum alone made it fail, and it passes
  with the two as they are; all 16 of `MessageReleaseTest` pass.
