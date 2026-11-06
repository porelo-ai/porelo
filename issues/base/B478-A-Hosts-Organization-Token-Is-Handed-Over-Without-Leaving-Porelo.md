# [B478](README.md#b478) — A Host's Organization Token Is Handed Over Without Leaving Porelo

**Status:** Review. Accepted on the operator's word (2026-11-06), porelo's part of the window's request that a host get
its organization token from it.
**Size:** S - one verb that pipes a declared vault entry over the host card's pinned SSH; its tests. Estimate 60
minutes.

**What must be true.** The token a host needs to refresh its organization is handed to it without the value leaving
Porelo or reaching the window: one command reads the token declared for the organization's forge address from this
machine's vault and writes it to the host's `porelo-host-person org refresh` on standard input, over SSH pinned to the
host key of the organization's host card. Nothing else carries it: not an argument, not the environment of a process
the window starts, not a log line, not an answer.

## Found

- `porelo host unlock` sends a share to a host the same way: SSH with `StrictHostKeyChecking=yes` against a
  known-hosts file of the pinned key alone, the value on standard input, no agent forwarded
  (`HostUnlockCommand`).
- The token for an organization's forge is found as `OrganizationAccess.forgeToken` finds it: declared for the https
  address, else held in the vault under the forge's name.
- A host card carries the host's address, SSH port and public host key (`HostCard`), read from the organization's
  accepted state.

## Plan

- `porelo org host refresh <host>` (name to be settled with Agent Frontend): the host card from the organization's
  state, its key pinned; the token by `forgeToken`; refused before any connection when the card, the key or the token
  is missing, or the vault is shut.
- The window calls it as a command or a daemon method that answers only done or why not; the value never crosses
  varlink.
- Red tests: the value reaches the remote command's standard input and no argument, environment or output; a host
  key other than the card's refuses before the value is sent; a missing token says which declaration to make.

## Open questions

- The exact remote command line and its exit codes, from porelo-host's `porelo-host-person org refresh`.

## Done

- `porelo org host refresh HOST [--json]` (`OrgHostRefreshCommand`): the host's accepted card first, then the token as
  `OrganizationAccess.forgeToken` finds it, then `porelo-host-person org refresh` over ssh as root, the account a host
  admin's key opens on a host, pinned to the card's key alone (`PinnedHostSsh`, now shared with `host unlock`). The token
  goes on that command's standard input and nowhere else.
- Refused before any ssh: a name no host has (64), the vault shut (77), no organization (78), no such host (66), no
  card (65), no key on it (69), no token (69, naming the `credentials declare` to make). The host's exits 64, 65, 69
  and 73 are passed on; ssh's 255 is 69; anything else, or an answer that does not read, is 70 and claims nothing.
- `--json` answers `handedOver` and the host's own answer where it holds no trace of the token.
- Tests (`OrgHostRefreshCommandTest`): the token reaches the remote command's standard input and no argument,
  environment or output; another host key refuses before it is read; each missing piece refuses before ssh; the host's
  exits; no echo of the token in the host's output, its diagnostics or an exception. With the token kept off standard
  input, the first fails.

