# B775 — An Agent's Sign-In Carried Sealed to a Host

```json
{
  "$schema": "issue-1.json",
  "id": "B775",
  "title": "An Agent's Sign-In Carried Sealed to a Host",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 132,
  "blockedBy": ["B774"],
  "covers": "A person copies the sign-in a local agent holds on this machine into their vault on a host, read by the agent's own reader and sealed as a vault entry is sealed",
  "openQuestions": 0
}
```

**Why this size.** S: a second source for [B774](README.md#b774)'s `SealCredential`, reusing `CredentialImport`'s reading of an
agent's own file, with tests and contract lines. Estimate: 38 minutes (Core's median for S). Porelo's half of ST362's
second source (b); the per-agent readers are Agent Smith's, the dialog Agent Frontend's.

**What must be true.** For every provider an agent installed here holds a sign-in for, the person carries that
sign-in to a host from the window, without signing the agent in again there.

Filed 2026-11-14 under ST362 "Credentials carried to a host", from the operator's word.

## Plan

1. `SealCredential` takes either `name` ([B774](README.md#b774)) or `agent` and `provider` (and an optional `configDirectory`, as
   `ImportCredential`): the daemon reads the agent's sign-in as `CredentialImport` does, never writing this
   machine's vault, and seals it with the vault key and kind `CredentialImport` would store it under, so the host
   stores it where its tasks look.
2. `ImportableCredentials() -> (rows: [](agent, provider, kind))`, or the existing listing if one answers it: what
   the dialog offers, names only.
3. Outcomes as `ImportCredential`'s: `NO_SUCH_AGENT`, `NOTHING_TO_IMPORT`, `WRONG_PROVIDER`, no blob.

## Red-first test

- An agent's sign-in file sealed by agent and provider is received on the host under the key `ImportCredential`
  would use, with the same value; this machine's vault is unchanged.
- An agent with no sign-in answers `NOTHING_TO_IMPORT` and no blob.

## Risks

- A sign-in file that renews itself on the host (a refresh token) is then used from two machines; whether a provider
  revokes one of them is the per-agent reader's to say, and the dialog shows it.

## Blockers

[B774](README.md#b774).

## Acceptance

- The red-first tests pass; `CredentialImport`'s tests still pass.
- On the VM as host: a local agent's sign-in carried there starts a task with that agent.

## Open questions

None.

## To be checked

- Which agents' sign-ins are bound to this machine and must not be carried.
