# [B535](README.md#b535) — A Sign-In's Vault: Said When Locked Meanwhile, Kept Open After A Terminal Sign-In

**Status:** Implementing. Filed 2026-11-09 from [B530](README.md#b530)'s code reading, findings 4 and 8, as the
operator decided them (the operator's decision of 2026-11-09 on the open points of wave 2, points 1 and 2).
**Size:** S - two small changes in the sign-in's store and the terminal's vault opening, each with its test.

**What must be true.**
1. A sign-in whose vault was locked while it ran, and could not store what it made, says so in plain words: the vault
   was locked during the sign-in; unlock it and sign in again. Never a general "could not store".
2. `porelo vault login` at a terminal that opened a shut vault keeps it open in the kernel keyring until the machine
   restarts, as `porelo vault unlock` does, so the daemon and a window waiting on the sign-in read it signed in.

Point 2 is the operator's decision against Core's recommendation (leave the vault shut and let the window say "stored;
unlock to start"): opening the vault for longer was to stay the person's explicit act. The operator chose that a
sign-in at a terminal is that act.

## Plan

- 1: in `AgentLogin`'s store, a `VaultException` from an opener that no longer opens (a kept share asked again after
  `vault lock`) becomes the outcome `VAULT_LOCKED` with that sentence; `AgentSignInRuns` answers it as the wait's
  `reason`. Red first: a sign-in whose opener stops opening before the store answers VAULT_LOCKED and the sentence's
  effect (the outcome), not a general failure.
- 2: `VaultLoginCommand` opens a shut vault with the keep flag (`openIfShut(true, true, out)`), as `vault unlock`.
  Red first: after a terminal sign-in that opened a shut vault, the keyring holds the vault's key.
- Docs: doc/credentials.md (a terminal sign-in leaves the vault open until restart).
