# [B497](README.md#b497) — A Forge Token Lent For One Command

**Status:** Backlog; waits on the host's helper offering `forge-token --lend` on the host this runs on.
**Size:** XS - the two lending callers and a unit test.

**What must be true.** The gate's git commands against the host's forge and a task start's taking of a project ask the
host's helper for a token lent for that command (`porelo-host-person forge-token --lend`), which leaves the person's
own token valid, so the builds watch, which reads the person's token from the vault, is not refused after the next
push, refresh or start.

## Plan

- `HostForgeLending.token()` and `HostForgeAccess.token()` call `sudo -n /usr/local/sbin/porelo-host-person
  forge-token --lend`; nothing else changes, and the builds watch keeps reading the vault entry.
- Red first: a unit test that the two callers ask with `--lend`.
- Built only once a host's helper takes `--lend`: a helper without it refuses, and a task start would be refused.

## Acceptance

- Both callers send `--lend`; on a host with the helper that takes it, builds stay followed across a push.
