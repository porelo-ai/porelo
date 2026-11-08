# [B499](README.md#b499) — A Vault Entry Removed And Replaced Over The Socket

**Status:** Backlog.
**Size:** M - two methods in the vault's contract part (`30-vault`), `VaultMethods`, and their tests.

**What must be true.** A client of the daemon removes a vault entry - its secret, not only the record
`CredentialForget` forgets - and replaces an entry's value, as `porelo vault remove` and `porelo vault put` do at the
machine. The value reaches the daemon as a parameter and is never logged, echoed or answered; a shut vault is refused
as `vault-shut`, an unknown entry as its own refusal, and an entry a running task borrows says so.

## Plan

- `CredentialRemove(name)` and `CredentialReplace(name, value)` in `30-vault.varlink`, implemented in `VaultMethods` on
  the same code as `vault remove` and `vault put`; `InterfaceDescriptionTest` holds the part.
- Red first: a unit test that a removed entry's value is gone from the vault file, and that the value of a replace
  appears in no reply and no log line.

## Acceptance

- Removed: the entry and its value are gone; replaced: the new value is read back by the next task; shut, unknown and
  borrowed are refused by name.
