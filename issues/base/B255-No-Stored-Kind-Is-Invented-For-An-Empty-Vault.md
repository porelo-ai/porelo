# B255 — No Stored Kind Is Invented for an Empty Vault

**Status:** Review: published in `0.5.1~snapshot.320`; green on the VM in its acceptance suite.
**Size:** XS - one condition in `CredentialChoice.unbrokerable` and a test.

**What must be true.** A start with no credential stored for its agent, and none asked for with `--credential-type`,
says that the credential is missing. It never names one "stored" that is not there.

## Why

Measured by porelo-codex's agent on `0.5.1~snapshot.317`: an unattended Codex task in an empty vault, its agent's
default provider `chatgpt`, was refused with "the 'api-key' credential stored for 'codex' cannot be used", and the
guidance to use `openai` instead. Nothing was stored.

Read in the code: `CredentialChoice.credentialType` answers `api-key` when the vault holds no entry, and
`unbrokerable` then holds that invented kind against the provider's `unbrokerable` map. `TaskLaunch` asks
`unbrokerable` before `unavailableFor`, which would have said the credential is missing.

## The shape proposed

- `unbrokerable` refuses nothing when no entry is stored and no kind was asked for; the missing credential is then
  said where it is said today.
- A kind asked for with `--credential-type` and refused by the provider is still refused before the start, in words
  that do not say "stored".

## To be checked

- That porelo-codex's provider-free scenario for a missing credential passes on it, unchanged.
