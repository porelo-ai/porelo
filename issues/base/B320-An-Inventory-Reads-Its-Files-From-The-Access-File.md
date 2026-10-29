# B320 — An Inventory Reads Its Files From The Access File

**Status:** Review.
**Size:** XS-S - the machine file's second bundle, verified beside the project's, and `files` read from the applied
access file; docs and tests.

**What must be true.** `porelo project inventory --machine-file` verifies the access bundle the machine file names
beside the project's, and each `porelo_transports` entry's `files` comes from the applied access file instead of the
public `project.yml`. The answer keeps its shape and stays version 2. porelo-host's play still finds where a transport
expects a file it writes, such as Matrix's `registration_token_file`, once that setting lives only in the private
access file.

## Plan

Read in the code on 2026-10-28.

- **Today:** `ProjectInventoryCommand.transports(project)` lists each scheme once with its package, and `files` holds
  the transport's `*_file` settings from the public `project.yml`, paths only. A setting that is not one path
  (absolute or under `~/`, without `..`, on one line), or not a string, refuses the inventory (65). The refusal names
  the setting and never its value. `MachineFile` names the project, its key and the keys for the vault.
- **The plan:** `MachineFile` takes an optional `access: {bundle: <path>}`, refused outside the file's directory like
  a key's path. The inventory verifies both bundles with [B311](README.md#b311)'s check, and reads `files` from the
  applied access file. The path rules and the refusal stay as they are.
- **Docs:** `doc/hosts.md` (the machine file's `access`, the inventory). **Red first:** an inventory test with a
  Matrix access file whose `registration_token_file` is absent from the public project file.

## Acceptance

- Seen to fail first, then green, in `app-project` tests.
- An access bundle that does not verify refuses the inventory, and the failure has been made to happen once.
- `doc/hosts.md` shows the machine file with `access`.

## To be checked

- None. Built as planned; a machine file's `access` bundle is refused for a project naming no access repository, and a
  project naming one needs it, because its URL is never fetched.
