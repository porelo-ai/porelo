# B313 — An Inventory From The Access File

**Status:** Implementing, part 1; placed in sprint 6i by the operator on 2026-10-28, beside porelo-host's
[HO41](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho41).
Accepted and estimated the same day, from
[PJ78](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj78).
**Size:** S - the machine file's second bundle, the inventory's `files`, docs and tests.

**What must be true.** `porelo project inventory --machine-file` verifies the access bundle the machine file names
beside the
project's, and each `porelo_transports` entry gains `files`: the transport's `*_file` settings from the applied
access file, paths only; the inventory stays version 2.

**Split, the operator's on 2026-10-28.** Part 1, built now: `files` from the public `project.yml`, inventory version
2, so [HO41](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho41)'s play finds the laptop
homeserver's `registration_token_file` while that file is still public. Part 2,
with [B310](README.md#b310): the machine file's access bundle, verified beside the project's, and `files` read from
the applied access file instead. The answer stays the same; only where it is read from changes.

A reading by a second agent changed part 1: a `*_file` setting that is not one path, absolute or under `~/`, without
`..` and on one line, or not a string at all, refuses the inventory (65), naming the setting and never its value,
since a value there may be a secret written under the wrong name; and `porelo talk dismiss`'s help, the contract's
comment and `doc/transports.md` say that it takes the machine off the whole server.

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
