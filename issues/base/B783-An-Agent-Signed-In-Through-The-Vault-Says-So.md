# B783 — An Agent Signed In Through the Vault Says So

```json
{
  "$schema": "issue-1.json",
  "id": "B783",
  "title": "An Agent Signed In Through the Vault Says So",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 12,
  "blockedBy": [],
  "covers": "An agent that drives a provider whose credential the person's vault holds is answered as able to start, signed in through the Porelo-Vault to that provider, not as not signed in, wherever the daemon tells a start's choice what it found",
  "openQuestions": 0
}
```

**Why this size.** M: what the daemon answers for an agent's sign-in where a start is chosen, read from the vault as
the start reads it, which the window renders, with its tests and the contract's comment. Estimate: 63 minutes
(Core's median for M).

**What must be true.** A person whose vault holds a provider's credential - carried there from another machine,
with no sign-in files of the agent on this one - sees the agent that drives that provider as ready to start, signed
in through the Porelo-Vault to that provider, and a start uses that credential.

## Why

Seen on a host: a start's dialog offered "Claude Code · not signed in here", while the host's providers showed
Anthropic authenticated by the credential carried into the person's vault. `AgentChoice.read` answers
`NOT_SIGNED_IN` from what the agent finds of itself alone (its own sign-in files) and looks at the vault only to order
findings that are signed in already. The start itself decides by the vault (`StartCheck`, refusing only when the
vault holds no credential for the provider's entry), and `AgentSignIns` judges by the vault too
(`AgentSignInRuns.state`), so the answer a person sees and what the start does disagree.

## Plan

1. `AgentChoice.read`: with no finding signed in, an agent whose drivable provider's entry the vault holds, as
   `SelectedProvider.credentialKey` and `CredentialHeaders.lacking` judge it for a start, is found able to start,
   by the vault, naming that provider; a shut vault stays unknown, never "not signed in".
2. The reply the window renders for it carries that as a value the window can name ("through the Porelo-Vault"),
   an added value, not a sentence; its comment in the contract says so.
3. `porelo task start`'s line for a chosen agent says "signed in through the Porelo-Vault to <provider>" in the same
   case.

## Red-first test

- An agent with no sign-in files of its own and a vault holding its provider's credential: the choice answers it as
  able to start through the vault, with that provider. Fails today with `NOT_SIGNED_IN`.
- The same with a shut vault: unknown, never not signed in. With an empty vault: not signed in, as today.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, the start dialog offers the agent as signed in through the Porelo-Vault, and a start runs with
  the carried credential.

## To be checked

- Which daemon reply and field the window's "not signed in here" renders (asked of the window's agent), so the plan
  changes that reply and no other.
