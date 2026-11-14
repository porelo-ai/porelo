# B775 — An Agent's Sign-In Carried Sealed to a Host

```json
{
  "$schema": "issue-1.json",
  "id": "B775",
  "title": "An Agent's Sign-In Carried Sealed to a Host",
  "state": "Review",
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

## Built

Built by an internal Claude Code sub-agent, reviewed and tested by Core.

- `CredentialImport.read` (apps/app-vault): the reading half of an import - agent, config directory, provider, the
  sign-in, the vault name and the entry an import would store - writing no vault. `run` calls it and keeps its order
  and outcomes; `CredentialImportTest` is unchanged and green.
- `CredentialImport.importable` and `ImportableCredentials() -> (rows: [](agent, provider, kind))`: names only, never
  a value. It starts each agent and asks it once per provider it drives, so it is not cheap. An agent that is not
  brokered is listed with provider `""`; carrying that row sends `agent` alone.
- `SealCredential` takes `name`, or `agent` with `provider` and an optional `configDirectory`. The sign-in is sealed
  under the name and kind an import would store it under, and this machine's vault is neither opened nor written.
  Outcomes add `NO_SUCH_AGENT`, `NO_CONFIG_DIRECTORY`, `NOTHING_TO_IMPORT`, `WRONG_PROVIDER` and `FAILED`; `name`
  and `agent` together answer `FAILED`. A blank `provider` is passed on as `ImportCredential` passes it, and refused
  there: read as none, it would seal whatever the agent answers (Core's fix to the sub-agent's cut,
  `aBlankProviderBesideAnAgentSealsNothing`, seen failing first).
- `ImportableCredentials` is in `McpMethods.NEVER`. Contract comments in `30-vault.varlink`, a paragraph in
  `doc/hosts.md`, changelog `agent-sign-in-carried-to-a-host.yml`.
- Unlike an import, a seal by agent does not refuse an existing entry under another name than the agent's default;
  on the host, `ReceiveCredential` with `replace` false keeps an entry that is there.
- Tests in `CredentialCarryingTest` with a fake agent served over the agent protocol: the sign-in received on the host
  under the import's name, with the same value, this machine's vault byte-for-byte unchanged; sealed while this
  machine's vault is shut; `NOTHING_TO_IMPORT` and no blob; `name` and `agent` together refused; the importable list.

### Reading (Codex gpt-6.1-sol, one reading)

- Red, fixed: `run` built the entry before deciding a shut vault and its guard, so an entry `VaultEntry` refuses
  answered `FAILED` where it answered `VAULT_LOCKED` or `WRONG_PROVIDER` before. `Reading.entry()` and the note are
  now made when asked, after those checks, as before the split. No test pins this order; the change restores it by
  construction.
- Red, fixed: a sign-in that failed to be read answered with the agent's own words, which may quote its file. A seal
  says `FAILED` with fixed words now (`anAgentsFailureIsSaidWithoutItsWords`, seen failing first). An agent's kind
  that is not a plain name is neither sealed nor listed (`CredentialImport.plainKind`). Left: `ImportCredential`
  itself still answers the agent's words, as it did before this change; that is for its own issue.
- Red, fixed: a blank `provider` without `agent` sealed a vault entry; `name` with a blank `provider` sealed one too.
  A present `agent` or `provider`, blank included, now asks for a sign-in, and beside a `name` answers `FAILED`
  (`aBlankProviderAloneAsksForASignInAndIsRefused`, seen failing first; the blank-provider test asserts
  `WRONG_PROVIDER` now).
- Red, fixed: the contract said `name` is "" unless sealed; it is "" only when nothing was read.
- Yellow, fixed: `doc/hosts.md` no longer speaks of renewal; what is carried is what an import stores.
- Yellow, left: `ImportableCredentials` asks each agent once per provider with no overall deadline; the fake agent
  returns a constant rather than reading a file; machine-bound sign-ins are not filtered (the issue's "to be
  checked").

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
