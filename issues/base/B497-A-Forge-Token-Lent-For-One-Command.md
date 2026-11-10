# B497 — A Forge Token Lent For One Command

```json
{
  "$schema": "issue-1.json",
  "id": "B497",
  "title": "A Forge Token Lent For One Command",
  "state": "Review",
  "size": "XS",
  "priority": "Medium",
  "rank": 64,
  "blockedBy": [],
  "covers": "The gate and a start ask the host's helper for a token lent for one command, leaving the person's token valid; needs `porelo-host` from `2b17e7e`."
}
```

**Why this size.** the two lending callers and a unit test.

**What must be true.** The gate's git commands against the host's forge and a task start's taking of a project ask the
host's helper for a token lent for that command (`porelo-host-person forge-token --lend`), which leaves the person's
own token valid, so the builds watch, which reads the person's token from the vault, is not refused after the next
push, refresh or start.

## Plan

- `HostForgeLending.token()` and `HostForgeAccess.token()` call `sudo -n /usr/local/sbin/porelo-host-person
  forge-token --lend`; nothing else changes, and the builds watch keeps reading the vault entry.
- Red first: a unit test that the two callers ask with `--lend`.
- The host's helper takes `--lend` from `porelo-host` commit `2b17e7e` on. A host whose installed helper is older
  refuses `--lend` in sudo, so a start or a gate fetch there is refused: this ships in a release that requires that
  `porelo-host`, never before it.
- Built 2026-11-08: both callers send `--lend`; `HostForgeLendingTest`, `HostForgeAccessTest` and
  `ProjectTakingTest` hold the command, seen red without it.

## Acceptance

- Both callers send `--lend`; on a host with the helper that takes it, builds stay followed across a push.
- The release notes name the `porelo-host` version this needs.
