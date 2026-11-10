# B593 — A Join Says When It Invited

```json
{
  "$schema": "issue-1.json",
  "id": "B593",
  "title": "A Join Says When It Invited",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "`JoinMessages` answers `invited: true` when a join invited an account the person already has, so the window says to accept the invitation."
}
```

**Why this size.** one field of the transport's join answer handed on by `JoinMessages`, its words and its test.

Implementing since 2026-11-10; in Review since 2026-11-10, until the operator's test. Built by Core.
Filed 2026-11-10 in `porelo-project` ST308, for porelo-frontend's F400.

**What must be true.** When a join invites a Matrix account the person already has into a second project's room,
rather than making one, `JoinMessages` answers `invited: true` (and no password, as the transport gives none);
otherwise `invited: false`. The window then says to accept the invitation instead of signing in with a password.

## Plan

The Matrix transport's `join` already answers `"invited": true` for an existing account of the same homeserver
(porelo-message-matrix's CHANGELOG); `MessagingMethods.joinMessages` drops it.

- `45-messaging.varlink`: `JoinMessages` gains `invited: bool` in its answer, said in words.
- `MessagingMethods.joinMessages` hands it on, `false` where the transport says nothing or not `true`.
- Red first: `MessagingMethodsTest` - a transport answering `invited: true` and no password reaches the answer as
  `invited: true`; one answering a login with a password reaches it as `invited: false`.

## Acceptance

- The window's F400 reads `invited` from `JoinMessages`; a join that made an account answers `false`.

## As built

- `45-messaging.varlink`: `JoinMessages` answers `invited: bool`. `MessagingMethods.joinMessages` hands on the
  transport's `invited`, `true` only when the transport says `true`, otherwise `false`.
- `doc/decisions.md` says the invitation beside the made account; changelog `join-says-invited.yml`.
- `MessagingMethodsTest`: an invitation answers `invited: true`, a made account `false`; both red before the change
  (the field was absent).
