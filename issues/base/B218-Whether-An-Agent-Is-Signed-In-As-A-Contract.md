# B218 — Whether An Agent Is Signed In, As A Contract

**Status:** Idea.

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

## To be checked

- How `package` is known for an agent found by its description, and whether `found_by` is enough without it.
- What `signed_in` means for an agent with more than one credential, or one granted by `vault authorize`.
