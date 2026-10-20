# B179 — A Code Beside Why A Release Cannot Go

**Status:** Analyzed.
**Size:** M - small in code, one module's six returns and one reply, but a contract the interface reads, with a new
type and two cases no test covers yet.

**What must be true.** When a person's release of a held or refused message is answered `NOT_DELIVERABLE`, the reply
of `Release` in `45-messaging.varlink` carries a code beside its `detail` sentence - `NOT_IN_CONVERSATION`,
`REFUSED_BEFORE`, `UNCHECKED`, `NO_ADDRESSEE`, `PEER_NOT_ALLOWED`, `SIGNATURE_MISSING` - so a client can say what to do
for each case without matching on an English sentence that is no contract. The field is added, so a client that does
not read it sees no change.

Asked for by `porelo-frontend`'s [F117](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f117),
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
