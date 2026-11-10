# [B594](README.md#b594) — A Room For Default Is Refused By Name

**Status:** Implementing, since 2026-11-10. Filed 2026-11-10 in `porelo-project` ST30 (Work without a project, with plain refusals), porelo's part of its PJ14.
**Size:** S - one refusal, said where a conversation or a peer is set up for `default`, in egress's words; its tests.
**Blocked by:** -.

**What must be true.** A transport or a peer for project `default` is refused, naming a project repository as the
way, as a change to `default`'s egress already is (`EgressControl.DEFAULT_REFUSED`). Today a conversation set up
for `default` is accepted, and a task's enrolment skips `default` without a word.

## Plan

Read in `TaskConversations` (`setUp`, `enrolling`'s skip of `default`), `TalkPeersCommand`, the daemon's
`SetUpMessages`/`JoinMessages`/peer methods and `EgressControl`.

- One refusal text beside `DEFAULT_REFUSED`'s: `default` talks to nobody; a project repository is the way, with its
  project.yml. `TaskConversations.setUp` and `TalkPeersCommand` refuse with it (exit 78, as a refused setting),
  before any transport runs; the daemon's methods answer it as `ConversationRefused`.
- A start in `default` keeps starting without a room; its start steps say once that `default` has no room, instead
  of skipping without a word.
- Red first: a conversation set up for `default` is accepted today (`TaskConversationsTest`); a peer added for
  `default` likewise; the daemon's `SetUpMessages` for `default`.

## Acceptance

- `porelo talk setup default` and a peer for `default` are refused with the words above and run no transport; a
  start in `default` says it has no room and starts.
