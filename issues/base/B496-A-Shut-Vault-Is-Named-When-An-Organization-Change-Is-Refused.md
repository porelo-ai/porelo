# [B496](README.md#b496) — A Shut Vault Is Named When An Organization Change Is Refused

**Status:** Implementing.
**Size:** S - `OrgWriting` and its unit test; the refusal value `vault-shut` exists already.

**What must be true.** When an organization change cannot read the organization to its tip because the person's forge
token could not be lent, and this computer's Porelo-Vault is shut, the refusal is `vault-shut` (exit 77) and says to
open the vault, instead of `cannot-say` with "see whether this person's forge token is set". With the vault open, the
refusal stays as it is.

## Plan

- `OrgWriting.answer`: a `cannot-say` refusal from the write, with `readableCredentials()` empty, becomes
  `vault-shut` with words naming the vault and how to open it, keeping the revision.
- Red first: a unit test with a write that refuses `cannot-say` and a shut vault, which answers `cannot-say` today.

## Acceptance

- Shut vault: `vault-shut`, 77, the words name the vault. Open vault: `cannot-say` as before.
