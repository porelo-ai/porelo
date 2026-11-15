# B783 — An Agent Signed In Through the Vault Says So

```json
{
  "$schema": "issue-1.json",
  "id": "B783",
  "title": "An Agent Signed In Through the Vault Says So",
  "state": "Review",
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

## Built

- `AgentChoice.read`: with no finding signed in, `byVault` asks the vault for the start the agent takes by default,
  then each other provider it can drive, judged by `AgentSignIns.holds` (`SelectedProvider.credentialKey` and
  `CredentialHeaders.lacking`, moved out of `AgentSignIns.row`, which uses it too). Held: `SIGNED_IN` with that
  provider and `throughVault`; a shut vault, or one that fails to read: `CANNOT_SAY`; else as before. A start
  through no provider is not judged. `porelo task start` takes the found provider (`TaskLaunch.foundProvider`), so
  a held provider that is not the default is the one started.
- The chosen agent's line: "signed in through the Porelo-Vault to <provider>".
- `AgentMethods.found`: the `Agents()` reply adds `foundVia: "vault"` in that case only; `40-agent.varlink` says
  so and that a client passes `foundProvider` as the start's provider. `doc/commands.md`, a changelog entry.
- Red first: the vault-held and shut-vault choices failed with `NOT_SIGNED_IN`, the dry run's line said "not found
  signed in", `AgentEntryTest` missed `foundVia`. The empty-vault and own-files tests are guards, green before.
- Built by a Claude Code sub-agent of Agent Core. Reading (Sonnet, one reading): no red. Fixed: a start through no
  provider is not judged; a vault that fails to read cannot say; the contract names the provider to pass. Left
  (yellow): a credential type the provider's route cannot broker is answered signed in and refused at the start
  (as `AgentSignIns` judged before; the check lives in `app-vault`); an agent with no `config_dir`, or whose
  adapter fails, stays `CANNOT_SAY` without asking the vault; the vault is read once per agent without a sign-in;
  `AgentSignInRuns.state` keeps its own copy of the judgment; no test drives a held non-default provider.
- Frontend's answer to the check: the dialog renders `Agents()`'s `agents[].found`, `foundProvider`, `foundPaths`.

## Acceptance

- The red-first tests pass, each seen red first.
- On the rented host, the start dialog offers the agent as signed in through the Porelo-Vault, and a start runs with
  the carried credential.
