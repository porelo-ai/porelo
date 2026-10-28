# B318 — A Vault Clear A Play Can Read

**Status:** Implementing
**Size:** S - `vault clear --json`, a transport's name and project on its item, the contract's item grown, tests.

**What must be true.** A program that runs `porelo vault clear` - porelo-host's `down` - can tell from named values,
never from a sentence, whether only a transport could not clear an account and which one, so it can decide to clear
with `--force` and name what a person still has to do.

## Plan

Read in the code on 2026-10-28.

- **Today:** `VaultClearing.clear` and `ClearCommand.render` exit 1 for every refusal - a task still holding a
  token, a locked vault without `--force`, a transport that could not clear. The transport case is only in the
  lines: a `conversation` item whose `what` is a sentence ("the matrix account of P on the server the project
  names") with the transport's words, then the `vault` item "a transport could not clear …". There is no `--json`.
- **Seen:** in 6i, `down` stopped at the vault after the host was dismissed and the laptop's homeserver taken off
  the VPN; the play could not tell that only the transport had refused
  ([HO](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md) side filed by porelo-host).
- **Steps:**
  1. `Clearing.Item` gains `transport` and `project`, empty where an item has none; the `conversation` items set
     both. The contract's `ClearedItem` (`10-common`) grows by the two fields, within `Tasks1`.
  2. `porelo vault clear --json` prints one object: `items` (each `Item.asMap()`) and `refused`, and the reason
     the vault was kept as a named value (`tasks`, `locked`, `transports`) beside it. The exit stays 1 for any
     refusal.
  3. `doc/` states the JSON where `vault clear` is described.
- **The test that goes red first:** a vault clear whose transport refuses answers `--json` with `refused: true`,
  the kept reason `transports`, and the `conversation` item naming the transport and project (red today: no
  `--json`). The harm: a play that guesses from a sentence clears with `--force` on the wrong refusal and drops
  a vault a running task still needs, or stops a give-back that could have gone on.

## Acceptance

- `porelo vault clear --yes --json` with a refusing transport, a task holding a token, and a locked vault: each
  answers its own kept reason; the transport's item names transport and project.
