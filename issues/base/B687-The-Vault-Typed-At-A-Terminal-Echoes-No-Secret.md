# B687 — The Vault Typed At A Terminal Echoes No Secret

```json
{
  "$schema": "issue-1.json",
  "id": "B687",
  "title": "The Vault Typed At A Terminal Echoes No Secret",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 41,
  "blockedBy": [],
  "covers": "Porelo's acceptance suite proves at a terminal that a typed vault passphrase is not echoed, that a stored key shows neither where it is typed nor in `vault list`, and that the vault file on disk does not hold the value.",
  "openQuestions": 0
}
```

**Why this size.** three scenarios in the acceptance suite, written from scenarios an agent repository already runs,
with the kit's terminal steps; no product change unless one of them fails.

Filed 2026-11-13. The Claude Code agent repository's acceptance holds three scenarios that test only Porelo's vault;
they belong in Porelo's own suite, and that repository drops them once Porelo holds them. Their text is in that
repository's `credential.feature`, lines 14 to 55.

**What must be true.** Porelo's own suite shows, as a person meets it:
- `porelo vault init` and `porelo vault unlock`, typed at a terminal, do not show the passphrase on the screen;
- `porelo vault put <name> --type api-key`, typed at a terminal, shows the value neither while it is typed nor in
  `porelo vault list` afterwards;
- the vault file, read as it lies on disk, does not contain the stored value.

**Why.** `vault-commands.feature` passes passphrases only by `--passphrase-command` or `--passphrase-stdin`, so no
scenario of Porelo's own types one at a terminal; an echo there would go unnoticed here.

## Plan

- One feature, `vault/vault-typed.feature`, with the three scenarios: a terminal on the machine, keys typed as a person
  would, and the screen read back with the kit's terminal steps.
- The value and the passphrase are the scenario's own fixed test strings, never a real credential; the file check reads
  the vault file's bytes and asserts the value is not among them.
- Red first: each scenario is seen failing once against a deliberately broken step (the value echoed into the screen
  check), so that it can fail.

## Acceptance

- The three scenarios pass on the ubuntu VM.
- Each was seen failing once.
