# [B499](README.md#b499) — A Vault Entry Removed And Replaced Over The Socket

**Status:** Review.
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
- Found while building: the vault's part names a shut vault `VAULT_LOCKED` everywhere (`CredentialCheck`,
  `ImportCredential`, `EnrollDevice`), so the refusal is one error `CredentialRefused(reason, name, tasks)` with
  `VAULT_LOCKED` rather than `vault-shut`; the other reasons are `NO_VAULT`, `NO_SUCH_CREDENTIAL`, `RESERVED`,
  `GRANTED`, `BORROWED`, `NO_VALUE` and `VALUE_UNREADABLE`.
- Found while building: `vault remove` revokes a grant and deletes a client registration at the service, and asks a
  person when it cannot; over the socket nobody can be asked, so such an entry is refused as `GRANTED` and left to
  `vault remove` at the machine. The shared code is `CredentialChange` in `app-vault`, on
  `VaultRemoveCommand.removeFrom` and `VaultPutCommand.replace`; a replace keeps the entry's kind and settings and
  never creates one. Converting an OpenSSH-wrapped RSA key with `ssh-keygen`, which `vault put` does, is not done over
  the socket: a key is read back with `StoredKey.of` before it is stored, and one that does not read is refused as
  `VALUE_UNREADABLE`.

## Acceptance

- Removed: the entry and its value are gone; replaced: the new value is read back by the next task; shut, unknown and
  borrowed are refused by name.
