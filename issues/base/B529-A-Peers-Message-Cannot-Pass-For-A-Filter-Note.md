# [B529](README.md#b529) — A Peer's Message Cannot Pass For A Filter Note

**Status:** Idea.
**Severity:** low - it costs every task it reaches a turn; it widens no reach and shows nothing.

**What must be true.** Only the filter's own answer to a task's message wakes that task as a filter note. A message
another task wrote, carrying `inReplyToMessageId` or a part whose `data.decision` reads `rejected`, wakes a task only
where it names that task, as any other task's message does.

## Why

The wake tells a filter note from the room's talk by its shape alone: an `inReplyToMessageId`, or a part with a
`decision`, and then wakes when the decision is `rejected` or `wouldReject` is set. A task writes its own message's
metadata and parts, so a message shaped like a refusal note wakes every task it is delivered to, whomever it names.
The inbox does not say today which messages the filter wrote and which a peer did.

## Acceptance

- A test writes a task's room message with `inReplyToMessageId` and a part `{"data":{"decision":"rejected"}}` into
  another task's inbox, naming nobody: the wake does not wake it. The filter's own refusal note still wakes the task
  whose message it refused. Both are seen red first where they fail.

## To be checked

- How the filter's note can be told apart in the inbox: by the directory it is delivered through, by the host
  writing it (not the transport), or by a mark only the host can set; which one holds for a resumed task.
- Whether a plan reading is needed (it touches what a task can make another task do).
