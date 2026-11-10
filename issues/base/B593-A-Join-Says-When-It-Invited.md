# [B593](README.md#b593) — A Join Says When It Invited

**Status:** Implementing, since 2026-11-10. Filed 2026-11-10 in `porelo-project` ST308, for porelo-frontend's F400.
**Size:** XS - one field of the transport's join answer handed on by `JoinMessages`, its words and its test.
**Blocked by:** -.

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
