# B679 — A Task Start Reading The Vault Leaves No Core Dump

```json
{
  "$schema": "issue-1.json",
  "id": "B679",
  "title": "A Task Start Reading The Vault Leaves No Core Dump",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
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
