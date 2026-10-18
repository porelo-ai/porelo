# B205 — A Vault Named Elsewhere Clears Only Itself

**Status:** Backlog.
**Size:** S - one condition in `Clearing.transports` as `VaultClearing` calls it, one line in the listing, and a unit
test in `VaultClearingTest`; no change to the transports.

**What must be true.** `porelo vault clear` on a vault that `PORELO_VAULT` names removes only what that vault holds.
The account's own vault, its transport state and its homeserver stay as they were.

## Why

`PORELO_VAULT` exists so that a run, such as an acceptance run, does not read or write the account's own vault
(`VaultPaths.vaultFile`). But `vault clear` ends with the transports' account-wide `clear` and removes
`~/.local/state/porelo/transport/<scheme>` (`Clearing.transports`). Neither belongs to the vault being cleared: they
belong to the account, and the account's own vault still holds the tokens for them. So clearing a test vault takes
the account's homeserver with it, and the own vault is left holding accounts on a server that is gone. That is the
failure the clear was built to prevent, in reverse.

Found on 2026-10-18 when the clear was tried on the `fedora` VM before the rented walk: a clear with `PORELO_VAULT`
set removed the account's `~/.local/state/porelo/transport/idle`.

## Plan

1. **Red first:** in `VaultClearingTest`, a clear of a vault at a path other than the default, with a transport's
   state directory present, must leave that directory and must not run the transport's account-wide `clear`. The test
   is watched to fail against today's code.
2. In `VaultClearing`, the account-wide transport step runs only when the vault cleared is the account's own
   (`PORELO_VAULT` unset or naming the default path). Otherwise the listing says, for each transport, `not removed`
   with "it belongs to the account, not to the vault named by PORELO_VAULT".
3. Each project's account elsewhere is still cleared while the named vault holds its token, since that account was
   made with that vault's token.
4. `doc/commands.md` under `vault clear` gets one sentence for a vault named by `PORELO_VAULT`.

## Acceptance

- [ ] With `PORELO_VAULT` naming a vault in a temporary directory, `vault clear --yes` removes that vault, its backup,
      its lock and its cached passphrase, and leaves `~/.local/state/porelo/transport/` and the account's own vault
      untouched; the transports' account-wide `clear` is not called.
- [ ] Without `PORELO_VAULT`, the clear is as before, and the existing tests pass unchanged.
