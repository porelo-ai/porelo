# B218 — Whether An Agent Is Signed In, As A Contract

**Status:** Review.
**Size:** S - one `--json` form of `porelo agents` with one vault read, the page and tests.

**What must be true.** A program can ask Porelo, in a form kept stable, which agents are installed and whether
the vault holds each one's credential. So a host's play stops for an agent's sign-in only where one is missing.

## Why

porelo-host's `tasks` part, after QH20 (a), names `agent-login:<agent>` at its stop for each agent whose credential
the vault lacks. Today only text meant for a person says so. `porelo agents` has no `--json`, and `vault login`
knows a credential is there (its `--force` "logs in again even though this machine already has a credential"), but
says so in words.

## The shape proposed

- `porelo agents --json`: `{"agents": [{"name", "package", "found_by", "signed_in"}]}`. `signed_in` is true when
  the vault holds the credential the agent declares, and is left out with a reason when the vault is shut.
- `doc/hosts.md` names it beside the three answers a second run reads.

## Plan

Read on 2026-10-19 in `AgentsCommand`, `AgentLogin` and `StartCheck`:

1. **Where a login keeps its credential:** `AgentLogin` stores it under the name of the provider
   `SelectedProvider.choose` picks for the agent, or under the agent's own name when there is none. So "signed in" is
   "the vault holds an entry under that name". That is the same rule the login itself follows, and no new knowledge.
2. **`porelo agents --json`** prints one object:
   `{"vault": "open"|"shut"|"none", "agents": [{"name", "found_by", "credential", "signed_in"}]}`.
   - `credential` is the entry's name by item 1, which is also where `vault authorize` stores a grant (the
     provider's name); with no default provider, `credential` and `signed_in` are left out;
   - `signed_in` is true or false while the vault is open, and left out otherwise; `vault` says why. The vault is
     read with what the account keeps (`context.readableCredentials()`, as `StartCheck` reads it), so nothing asks
     for a passphrase.
   - Nothing of a value is printed.
3. **`package` is left out.** Porelo finds an agent by its description under `agents.d`, not by its package. A play
   gets the package from the inventory ([B217](README.md#b217)'s `agent_package`), so `found_by` is enough.
4. **`doc/hosts.md`** gains the row beside the three answers a second run reads.
5. **Tests, each seen red first:** an open vault holding the entry, and one without it; a shut vault and none, each
   without `signed_in`; nothing secret in the output.
6. A changelog line.

## Acceptance

- On the VM as `core`, with the vault open: `porelo agents --json` names each installed agent with `signed_in` true
  only for one whose credential is in the vault; with it shut, `vault` is `shut` and no `signed_in` is given.
- Machines' `tasks.yml` names `agent-login:<agent>` exactly for the agents answered `signed_in: false`.
