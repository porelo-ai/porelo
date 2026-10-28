# B313 — An Inventory From The Access File

**Status:** Estimated, released by the operator on 2026-10-28 with this size; accepted the same day, from
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78).
**Size:** S - the machine file's second bundle, the inventory's `files`, docs and tests.

**What must be true.** `porelo project inventory --machine-file` verifies the access bundle the machine file names
beside the
project's, and each `porelo_transports` entry gains `files`: the transport's `*_file` settings from the applied
access file, paths only; the inventory stays version 2.

## Why

[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78) (QH57): porelo-host's play must know
where a transport expects a file it writes, such as Matrix's
`registration_token_file`, and with (b) that setting is in the private access file only.

## Plan

Read in the code on 2026-10-28.

- `ProjectInventoryCommand.transports(project)` lists each scheme once with its package. `MachineFile` names the
  project, its key and the keys for the vault.
- **The plan:** `MachineFile` takes an optional `access: {bundle: <path>}`, refused outside the file's directory like
  a key's path. The inventory verifies both ([B311](README.md#b311)'s check) and adds `files: {<setting>: <path>}` per
  transport, from
  every setting whose name ends in `_file`. No `files` key when there is none.
- **Docs:** `doc/hosts.md` (the machine file, the inventory), `doc/transports.md` (a setting named `*_file` is a path,
  never a value). **Red first:** an inventory test with a Matrix access file.

## Acceptance

- Seen to fail first, then green, in `app-project` tests.
- `doc/hosts.md` shows the inventory with `files`.

## To be checked

- None.
