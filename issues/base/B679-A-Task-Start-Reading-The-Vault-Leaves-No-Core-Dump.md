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

- **Where the vault is decrypted.** `VaultFile` calls `ProcessHardening.disableDumping()`, as the brokers do, before
  it unwraps the master key with any opener. Every read and every change of a slot goes through that one place. So
  a task's start is undumpable from its first vault read on, whichever reads it: the credential check through
  `PoreloContext`, `TaskSecrets`, the stored authorization, the command mask. So is every other command that
  decrypts the vault. Asked on every opening; it costs one system call and holds for the process's life.
- **A process that cannot be made undumpable decrypts nothing.** The failure becomes a `VaultException` that says
  so, which every reader already reports, instead of an unhandled error.
- **Red first.** `VaultFileTest.aProcessIsUndumpableOnceItDecryptsTheVault` and
  `PoreloContextTest.aProcessThatDecryptsTheVaultIsUndumpableFromThatReadOn` found the process still dumpable after
  a read.
- **A code reading found no Red.** Its first Yellow, reads in the start that bypass `PoreloContext`, led to the one
  place in `VaultFile` instead of calls in `PoreloContext`; its second, an unhandled hardening error, is the
  `VaultException` above.
- **Kept.** No zero core limit: the brokers set none either. With the flag the kernel writes a core only where
  `fs.suid_dumpable` is 2, and then readable by root alone, never by the account.
- **Docs.** `doc/decisions.md` says every process decrypting the vault makes itself undumpable first. Changelog under
  *Fixed*.
- **Locally.** vault (317), supervisor (320), organization/git-tree (371) and apps/app (4726) passed whole, one at a
  time, and so did the root's `validate` and `-Pdocuments test`.
