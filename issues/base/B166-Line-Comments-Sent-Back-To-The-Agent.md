# [B166](README.md#b166) — Line Comments Sent Back To The Agent

**Status:** Analyzed.
**Blocked by:** [B136](README.md#b136), [B150](README.md#b150), [B291](README.md#b291), [B292](README.md#b292), [B293](README.md#b293).
**Blocks:** [B171](README.md#b171), [B172](README.md#b172), [B176](README.md#b176), [B163](README.md#b163).
**Size:** L - a shared review contract, host store, CLI, message path and acceptance across the filter, interface and IDE plugin.

**What must be true.** A person can comment on lines, a file or the whole review of waiting work, and a reject
carries those comments into the task as untrusted review data. Comments, findings, suggestions and replies use the
shared SARIF profile; code and commits travel only through the gate.

## Plan

`GateReviewCommand` prints `GitGate.rankedReview`; `35-gate.varlink` serves `Review`, `Approve` and `Reject`, without
comment methods or types. `PersonNote.rejectedWork` delivers one reason. `MailboxGuide` and `MessageDispatch` handle
messages; `ReviewComments` is a new class. The narrowed message schema ([B108](README.md#b108)) comes later and is
not needed.

1. Red first: a real-repository test in `gate` submits a SARIF line anchor beyond the pinned blob's end and sees it
   refused; a valid anchor is stored. Add forged-origin, cross-file replacement and conflicting-coordinate cases.
2. Add `gate/.../ReviewComments.java`: owner-only SARIF storage under `porelo/comments/<push name>/`, serialized
   writes under a lock and a journal of comments, replies, releases and resolution. Bind project, repository, exact
   head/base commits and recorded task ID. Pin referenced commits under hidden `refs/porelo/reviewed/` refs and hide
   them in `GitHttpServer`; reject and subsequent pushes must not make old anchors unreadable.
3. Use [B291](README.md#b291)'s run/result model through [B292](README.md#b292). Stamp UUIDs, thread membership,
   producer, role, source level and completeness from authenticated host context. Comments use `review/none` and
   findings `fail` with explicit rule and level. `porelo/threadId`, `porelo/replyTo` and `porelo/threadState` carry
   conversation state. Neither suppressions nor a shared `correlationGuid` represents a thread.
4. Validate canonical artifact URIs against pinned Git objects, never host paths or fetched URLs. Check old-side
   anchors at the pinned review base and new-side anchors at its head. Check inclusive line spans and resolved
   Unicode columns. A suggestion is one exact replacement of its new-side regular-blob anchor, within the shared
   limits, never automatically applied. Source/release restrictions are [B293](README.md#b293)'s; outdated or
   old-side suggestions have no apply action.
5. In `apps/app-gate`, add `gate comment` and `gate comments`. Support line/range, file and general comments, optional
   suggestion file and reply target. Add `Comment`, paged `Comments` and explicit `ResolveThread` methods in
   `GateMethods` and `35-gate.varlink`, carrying bounded SARIF documents rather than parallel Anchor/Comment/Thread
   records. Refusals name invalid anchor, suggestion or size; mutations are authenticated and journaled.
6. Carry one SARIF data part on today's envelope, only on a message whose kind is `comment`. The filter checks envelope,
   allowed fields and content; the host checks Git objects and authenticated authority. Integrate with
   the message filter's check of the `comment` kind before acceptance of
   agent replies. `MessageDispatch` routes authorized `to: review` submissions locally; it cannot make
   `app-messaging` depend on `app-gate`. Register the handler from the assembly boundary or use a lower-level
   callback. Refuse another task's thread and bounce safely through `HostBounce`.
7. `GateRejectCommand`, daemon `Reject` and `PersonNote.rejectedWork` deliver the open threads in structured data,
   never prompt instructions. Local person input bypasses the message filter but passes the same profile,
   structural and Git checks. Generate at most three context lines on each side from authorized blobs for local/task
   delivery only, bounded to 8 KiB; never export that context or retain sender snippets.
8. Update `MailboxGuide`, `doc/commands.md` and `doc/messages.md`. A reviewer sees the same store through the daemon,
   interface and IDE plugin. Preserve a comment's original revision; [B176](README.md#b176) adds relocation. The
   journal integrates with [B154](README.md#b154) when available without blocking the comment flow.

## Acceptance

- In `gate-review.feature`, two comments and a reject reach the stub as one structured review message; its reply
  appears in the same thread with host-stamped task origin. Delivery wakes a resting agent through the message path.
- Out-of-blob anchors, cross-file or oversized fixes and task-supplied authority are refused. A local person's input
  receives structural validation too. Review prose cannot become a prompt instruction or command.
- A new push preserves the old comment and commit; no task can advertise the hidden review refs. File and general
  comments work without invented line anchors, and pages never imply that omitted results are absent.
- Seen red first: unauthorized thread access, an actionable old-side fix, hidden-ref disclosure and loss of an old
  anchor after reject. The daemon contract and CLI use the same host implementation.

## To be checked

- Choose SARIF persistence granularity and cursor format within the profile's bounds; measure concurrent CLI/daemon
  writers and recovery after interrupted publication.
- Profile/schema work may land first; the message acceptance path waits for the matching filter.
- Specify retention of the store and hidden pins at approve, removal and name reuse, including archived review data
  needed by [B176](README.md#b176) and [B289](README.md#b289).
