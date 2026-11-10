# B535 — A Sign-In's Vault: Said When Locked Meanwhile, Kept Open After A Terminal Sign-In

```json
{
  "$schema": "issue-1.json",
  "id": "B535",
  "title": "A Sign-In's Vault: Said When Locked Meanwhile, Kept Open After A Terminal Sign-In",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 43,
  "blockedBy": [],
  "covers": "A sign-in whose vault was locked meanwhile says so plainly; a terminal `vault login` keeps a vault it opened open until restart (the operator's decision)."
}
```

**Why this size.** two small changes in the sign-in's store and the terminal's vault opening, each with its test.

Filed 2026-11-09 from [B530](README.md#b530)'s code reading, findings 4 and 8, as the
operator decided them (the operator's decision of 2026-11-09 on the open points of wave 2, points 1 and 2).

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

## As built

- 1: `AgentLogin.store` catches the vault's refusal; where the opener taken before no longer opens, the outcome is
  `VAULT_LOCKED` with "the vault was locked during the sign-in ... unlock the vault and sign in again". It is said a lock
  only as `vault status` tells shut from broken: the file is there and its header reads, porelod answered, the wait was
  not interrupted, and the opener no longer opens; any other vault fault is thrown and ends `FAILED` as before. `AgentSignInWait` answers it as `reason: VAULT_LOCKED`.
- 2: `AgentLogin.opening` opens with `openIfShut(true, true, out)`; the prompt reads as `vault unlock`'s.
- Seen red first: `AgentLoginTest.aSignInWhoseVaultWasLockedMeanwhileSaysSoPlainly`, and the acceptance scenario "a
  login asked for while the vault is shut ..." (agent-login.feature) against the deployed 9410f1627: `vault status`
  after the login answered 3 (shut).

## After the code reading

- A vault gone or damaged, porelod not answering, or an interrupted wait was said as a lock: now as itself
  (`aVaultThatBrokeDuringTheSignInIsNotSaidAsLocked`, seen red first).
- The watch stores the moment the login writes; the path after the run stored once more and, with the vault locked in
  between, said "not stored" about a credential in the vault. The watch's answer is kept and a stored one is final
  (`aSignInTheWatchStoredIsStoredThoughTheVaultIsLockedBeforeTheLoginEnds`, seen red with the check taken out).
- Left as they were: `vault login --credentials-from` (no login runs, so no lock can fall in between, save during the
  client pick) ends `FAILED`; a store fault other than a lock in the watch keeps the watch looking, as before. Point 2
  is proved by the acceptance scenario, not by a unit test: the terminal prompt has no seam.
