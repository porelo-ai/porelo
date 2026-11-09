# [B529](README.md#b529) — A Peer's Message Cannot Pass For A Filter Note

**Status:** Implementing.
**Size:** S - one predicate in `AgentWake` given the task's mailbox, and its tests; no contract changes.
**Severity:** low - it costs every task it reaches a turn; it widens no reach and shows nothing.

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
