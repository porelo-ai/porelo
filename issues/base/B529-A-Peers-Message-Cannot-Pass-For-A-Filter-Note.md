# B529 — A Peer's Message Cannot Pass For A Filter Note

```json
{
  "$schema": "issue-1.json",
  "id": "B529",
  "title": "A Peer's Message Cannot Pass For A Filter Note",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 32,
  "blockedBy": [],
  "covers": "Another task's message shaped like a refusal note no longer wakes every task it reaches."
}
```

**Why this size.** one predicate in `AgentWake` given the task's mailbox, and its tests; no contract changes.

**Severity.** low - it costs every task it reaches a turn; it widens no reach and shows nothing.

**What must be true.** Only the filter's own answer to a task's message wakes that task as a filter note. A message
another task wrote, carrying `inReplyToMessageId` or a part whose `data.decision` reads `rejected`, wakes a task only
where it names that task, as any other task's message does.

## Why

The wake tells a filter note from the room's talk by its shape alone: an `inReplyToMessageId`, or a part with a
`decision`, and then wakes when the decision is `rejected` or `wouldReject` is set. A task writes its own message's
metadata and parts, so a message shaped like a refusal note wakes every task it is delivered to, whomever it names.
The inbox does not say today which messages the filter wrote and which a peer did.

## Plan

Read in the code: the filter's answer and the host's own bounce (`HostBounce`) both arrive in `filter/feedback`;
`BounceDelivery.deliver` moves each into the task's inbox and keeps a copy under the mailbox's record
(`MessageLookup.answers`, `<record>/answers/<name>`), where the agent cannot write. A peer's message reaches the inbox
through the transport and has no such copy. The record outlives a stop, so a resumed task keeps it.

- `AgentWake.namesIt` takes the mailbox's answers directory. A message shaped like a note (an `inReplyToMessageId`,
  or a part with a `decision`) counts as the filter's answer only when the host's copy of the same name holds the same
  bytes; then it wakes when refused, as today. Any other message so shaped falls through to the naming rule below it.
- `announceHeld` passes `MessageLookup.answers(mailbox)`; no other caller.
- Red first in `AgentWakeTest`: a peer's message with `inReplyToMessageId` and `{"data":{"decision":"rejected"}}`,
  naming nobody, written into another task's inbox, does not wake it (today it does); the filter's refusal delivered by
  `BounceDelivery` still wakes the task whose message it refused; a peer's note-shaped message that names the task
  still wakes it.

## Acceptance

- A test writes a task's room message with `inReplyToMessageId` and a part `{"data":{"decision":"rejected"}}` into
  another task's inbox, naming nobody: the wake does not wake it. The filter's own refusal note still wakes the task
  whose message it refused. Both are seen red first where they fail.

## As built

- `AgentWake.namesIt` takes the directory of the host's own copies (`MessageLookup.answers(mailbox)`). A message shaped
  like a note counts as the filter's answer only when the copy of the same name there is a regular file holding the
  same bytes; a peer's note-shaped message, or a delivered note the task rewrote in its inbox, falls through to the
  naming rule. `announceHeld` passes the copies; there is no other caller.
- Tests in `AgentWakeTest`: `aPeersMessageShapedLikeARefusalWakesOnlyTheTaskItNames` and
  `aFilterNoteTheTaskChangedInItsInboxIsNoLongerTheFilters`, both red with the copy check made to accept everything;
  `aRefusalTheHostDeliveredWakesTheTaskWhoseMessageItRefused` (through `BounceDelivery`) and the existing
  `aFilterNoteWakesOnlyWhenItRefusedOrWouldHave`, both red with the copy check made to accept nothing. The first now
  goes through `announce`, so it is also red when `announceHeld` passes another directory than the copies.
- Found by the independent reading: a delivered note without any `metadata` (its id flagged by the detectors) was
  dropped before the copy check; it is now read as having empty metadata
  (`aRefusalTheHostDeliveredWithoutMetadataStillWakesTheTask`). A `to` entry counts only as a non-empty string, so a
  task of no known project (empty short name) is not woken by `""`, nor a task called `null` by `[null]`, and
  `[null, "writer"]` still names writer (`aBlankOrNonTextNameWakesNoTask`). Both red before the change.
