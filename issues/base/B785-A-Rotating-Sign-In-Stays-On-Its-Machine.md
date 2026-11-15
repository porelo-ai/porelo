# B785 — A Rotating Sign-In Stays on Its Machine

```json
{
  "$schema": "issue-1.json",
  "id": "B785",
  "title": "A Rotating Sign-In Stays on Its Machine",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "Carrying to a host refuses a sign-in whose refresh token the provider rotates, an agent's own or one the vault keeps renewable, and says to sign in on the host instead; an API key is carried as before",
  "openQuestions": 0
}
```

**Why this size.** M: one judgment of what rotates, used where a carry seals, lists and receives, with a new
outcome and an added field on the contract, its tests and `doc/`. Estimate: 63 minutes (Core's median for M).

**What must be true.** No sign-in whose refresh token the provider rotates is ever on two machines at once: a carry
to a host refuses it before anything is sealed and tells the person to sign in on the host; an API key and any
other kind without a refresh token are carried as before.

Filed 2026-11-15 under ST364, on the operator's decision of the two-machines question (variant C).

## Why

A provider login renews with a refresh token that the service rotates on every use. A copy carried to a host
renews there, or the developer's own session renews here, and whichever renews second holds a spent token: one of
the two sessions ends. `SealCredential` copies an agent's sign-in as `ImportCredential` reads it, and a vault entry
by name with its companions, so both paths can put one refresh token on two machines today. `LoginCredentials`
already keeps an import unrenewed for the same reason on one machine.

## Plan

1. One judgment, *rotates*, failing closed: a credential that holds a refresh token rotates until its definition
   declares a measured "does not rotate" (`refresh_token_rotation.<provider>.<kind>: does-not-rotate` at the
   definition's top level; a provider or kind not named rotates) - an agent's sign-in whose reading carries `refreshToken`
   (`Credential.REFRESH_TOKEN`; the kind alone does not tell: `oauth` is with and without one), and a vault entry with
   its hidden refresh-token companion (`LoginCredentials.REFRESH`, as `vault login` and `vault authorize` keep it).
   `api-key` and `access-token` (Copilot's GitHub token) are carried as before.
2. `SealCredential` refuses either with a new outcome `ROTATES` before anything is sealed; its detail says that
   the provider rotates the sign-in's token and that the agent signs in on the host, naming the host verb ([B786](README.md#b786)).
   Nothing of the value, the token or the agent's file reaches the detail.
3. `ImportableCredentials` rows and the vault listing's entries gain an added `rotates: ?bool`, so a window offers
   no carry it would be refused; absent reads as carried, as today.
4. `ReceiveCredential` refuses the same kinds, so a sender older than this cannot carry one either.
5. `30-vault.varlink` names the outcome and the field; `doc/` says which sign-ins stay on their machine and why.

## Red-first test

- An agent's sign-in holding a refresh token: `SealCredential` answers `ROTATES` and seals nothing. Fails today:
  `SEALED`.
- A vault entry with a refresh-token companion, by name: `ROTATES`. Fails today.
- An API key, by name and as an agent's sign-in: `SEALED`, as today.
- `ImportableCredentials` and the vault listing mark the first two `rotates`, the API key not.
- `ReceiveCredential` with a rotating kind: refused, nothing stored.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, carrying the person's provider login is refused with the words that name the host sign-in,
  and an API key is carried.

## Open questions

None. Agent Smith answered 2026-11-15: the reader says it, not the kind (claude-code `oauth` with or without one;
codex and agy always; pi and omp for `anthropic` only); whether each provider really rotates is measured in its
definition's issues (CC75, CX28, AG30, PI67, OM65), and until one declares "does not rotate" it is not carried.
