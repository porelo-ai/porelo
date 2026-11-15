# B781 — The Forge Token Command Says a Shut Vault by Its Code

```json
{
  "$schema": "issue-1.json",
  "id": "B781",
  "title": "The Forge Token Command Says a Shut Vault by Its Code",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 10,
  "blockedBy": [],
  "covers": "`porelo host forge-token` exits 77 when the vault is shut, apart from 69 for a missing forge, a refused sudo or a bad answer, and the store command the pull-request read names carries `--if-forge`",
  "openQuestions": 0
}
```

**Why this size.** M: one command's exit codes and one daemon answer's value, which the window reads, with their
tests and the command's row in `doc/commands.md`. Estimate: 63 minutes (Core's median for M).

**What must be true.** A program that runs `porelo host forge-token` without a terminal tells a shut vault from
every other refusal by the exit code alone, without reading the words, so it can offer to open the vault first; and
the command the daemon names for a missing host forge token does nothing on a host without a forge.

## Why

`HostForgeTokenCommand.call` answers 69 for a shut host vault ("run 'porelo host unlock <host>'"), for a shut
passphrase vault without a terminal and for a vault still shut after the prompt - the same 69 as missing forge
settings, an unsafe `porelo-host` or `sudo`, a refused `sudo -n` and an answer with no valid token. A caller that
must not parse words cannot tell them apart. Porelo already exits 77 for a shut vault in the organization's commands
(`OrgOutput`, `Refused.VAULT_SHUT`).

`GateMethods` names `["porelo", "host", "forge-token"]` as `storeCommand` of `MissingHostForgeToken` (and in the
refusal details beside it); run by a program on a host without a forge, it fails instead of doing nothing.

## Plan

1. `HostForgeTokenCommand.call`: the three shut-vault paths return 77 (a named constant beside the command, as
   `OrgOutput` maps `VAULT_SHUT`); every other refusal keeps its code. A missing vault stays 69, since nothing can
   open it.
2. `GateMethods`: both places that name the store command name `["porelo", "host", "forge-token", "--if-forge"]`.
3. `doc/commands.md`: the command's row says 77 for a shut vault; `35-gate.varlink`'s comment on
   `MissingHostForgeToken` says the command carries `--if-forge`.
4. The acceptance features are searched for the old 69 of a shut vault and for the store command's value.

## Red-first test

- `HostForgeTokenCommandTest`: a shut host vault, and a shut passphrase vault without a terminal, each exit 77; a
  missing vault and a refused `sudo -n` still exit 69. Fails today with 69.
- `PullRequestMethodsTest` and `PoreloDaemonTest`: `MissingHostForgeToken.storeCommand` ends with `--if-forge`.
  Fails today.

## Acceptance

- The red-first tests pass, each seen red first.
- The unit suite of the touched modules is green.
