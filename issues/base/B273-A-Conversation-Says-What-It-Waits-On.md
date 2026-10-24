# B273 — A Conversation Says What It Waits On

**Status:** Implementing, for sprint 2c, as porelo-frontend
[F158](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f158)'s counterpart.
**Size:** S - one field in `Tasks1`'s `ProjectMessages`, set from the one place `waits` is set, and its test.

**What must be true.** A project's conversation says what it waits on in a field an interface can act on, beside
the sentence for a person: `waitsOn` is `"vault"` while the vault is locked and `""` otherwise. An interface offers the
vault's unlock on that row without reading the sentence.

## Why

Read on 2026-10-24 for porelo-frontend [F158](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f158): `ProjectMessages.waits` (`10-common.varlink`) is a sentence for a person. Porelo sets it
to exactly one value besides `""`: `Conversations.VAULT_LOCKED`, from `Conversations.vaultWait`. An interface that
offered an unlock on that row would have to match the words, which change without notice. So that issue's *Needs you* row
for waiting messages has no way out today.

## Decided

- **A string, not a varlink enum**: `waitsOn: string`, one of `""` or `"vault"`. Its comment says that more values may
  come and that an interface shows an unknown one as `waits` alone. An enum would break a client that reads it strictly
  when a value is added, and `Tasks1` only grows.
- **One place:** `Conversations.vaultWait` gives both, so `waitsOn` is `"vault"` exactly when `waits` is
  `VAULT_LOCKED`. A test fails when they part.
- `waits` stays as it is.

## Plan

- `10-common.varlink`, `ProjectMessages`: `waitsOn: string` after `waits`, with the comment above.
- `Conversations.row` puts `waitsOn` from the same reading of the vault as `waits`: one small value with both, rather
  than two calls that could disagree.
- **Tests, red first:**
  - `ConversationsTest`, or the test that covers `Conversations.row`: with a vault file and no opener, `waitsOn` is
    `"vault"` and `waits` is `VAULT_LOCKED`; unlocked, or with no vault file, both are `""`;
  - `InterfaceDescriptionTest` stays green; the daemon's reply carries the field.
- `doc/` names nothing of `ProjectMessages`' fields today; a changelog file, *Added*.
- **Build estimate=45**, like porelo [B265](README.md#b265) (S, 16 minutes of Codex). Built by Codex `gpt-6.1-sol`
  `xhigh`, since it changes the contract.

## Acceptance

- With the vault locked, `ProjectMessages` carries `waitsOn: "vault"` beside its sentence; unlocked, `""`.
- The two come from one reading, and a test fails when they part.
