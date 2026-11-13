# B679 — A Task Start Reading The Vault Leaves No Core Dump

```json
{
  "$schema": "issue-1.json",
  "id": "B679",
  "title": "A Task Start Reading The Vault Leaves No Core Dump",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 40,
  "blockedBy": [],
  "covers": "A `porelo task start` that reads the vault to see which credentials it can serve makes itself undumpable first, as the brokers do, so a crash of the starting process never writes a decrypted credential to a core file."
}
```

**Why this size.** one call before the vault is read in the start, and its test.

Filed 2026-11-12 from Codex's reading of [B219](README.md#b219), straight into the backlog as the bug rule says.

**What was seen.** Read in the code, not run. `CredentialWiring` decrypts the vault in the ordinary CLI process to
check which repository credentials it holds, before it starts the broker. The broker disables core dumps before it
reads the vault; the starting process does not. With core collection enabled, a crash of the start after that read
can write the decrypted entries to a core file.

**What must be true.** Every process that holds a decrypted vault entry has made itself undumpable before reading
it.

## Plan

- Before the start's first vault read (`CredentialWiring`, and any other reader in the start), disable dumps the way
  the brokers do (`prctl(PR_SET_DUMPABLE, 0)` and a zero core limit), once per process.
- Red first: a test that reads the start's dumpable state after the credential check finds it still dumpable today.

## Acceptance

- A start that checks repository credentials is undumpable from that read on; a test fails while it is not.

## Done

- **One place before every read.** `PoreloContext.beforeDecrypting()` calls `ProcessHardening.disableDumping()`, as
  the brokers do. `PoreloContext` calls it before each of its three vault reads (the passphrase check in
  `requireOpener`, `readableCredentials`, through which `credentials()` reads too, and `readableGrants`), and the
  start's command mask before its own `vault.read`. So the start's credential check in `CredentialWiring` and
  `StartCheck`, which read through `PoreloContext`, is undumpable from that read on, and so is every other command
  that reads the vault through it. Asked on every read; it costs one system call and holds for the process's life.
- **Red first.** `PoreloContextTest.aProcessThatDecryptsTheVaultIsUndumpableFromThatReadOn` reads the credentials
  and the grants of a real vault through the kernel keyring and found the process still dumpable before.
- **Kept.** No zero core limit: the brokers set none either. With the flag the kernel writes a core only where
  `fs.suid_dumpable` is 2, and then readable by root alone, never by the account. Commands in `app-vault` that read through `VaultFile` directly, not through `PoreloContext`, are not in
  this issue; the brokers among them already call `disableDumping`.
- **Docs.** `doc/decisions.md` says every command process calls it before it decrypts the vault. Changelog under
  *Fixed*.
- **Locally.** apps/app (4726) passed whole, and so did the root's `validate` and `-Pdocuments test`.
