# [B554](README.md#b554) — A Forge Token's Reach Checked Against What A Move Needs

**Status:** Backlog. Filed 2026-11-09 for the operator's automated rehearsal of a move on a rented server, run by the
agents under a test identity whose token the operator makes once. Priority to be set by the operator; nothing built
before the operator's word.
**Size:** S - one more need in the founding's check, read-only, from the forge's own answers.

**What must be true.** Before a move, a person learns from porelo, without changing anything, whether their forge token
reaches what the move will ask of it in the organization: reading who owns it (members), making a project's
repository (administration), adding a host's read key to a repository (administration), pushing to and fetching from a
repository (contents), and changing a workflow file where the project has one (workflows). Each missing permission is
named with its forge's own name for it, so the token can be made right once, before the server is rented.

## Why

A token made without one of these permissions fails late: a missing members read in the first read of the
organization (403, `cannot-say`), a missing administration write at the project's opening or when the host is prepared
(422), a missing workflows write at the first push of a workflow change. In a rehearsal on a rented server each such
failure costs a rental. `porelo org create --check` already asks the forge whose the token is (`token`, `owner`); it
does not say what the token may do.

## Plan

- `org create --check` (and the same need in a project's founding check) gains `token-reach`: `ready` when the forge's
  answer for the token covers each permission above, `missing` naming each one lacking, `cannot-tell` where the forge
  does not say a token's permissions (then the check says which call would show it).
- Read from the forge's answers about the token itself and a read-only request per permission; nothing is made.
- Red first: a token answer lacking administration write reads `missing` with that permission named; a full one
  `ready`.
