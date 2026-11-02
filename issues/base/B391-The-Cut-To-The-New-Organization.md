# B391 — The Cut To The New Organization

**Status:** Review.
**Size:** L - the commands of `identity` put on a new store, the removal of the old registry's commands, daemon methods and
adapter across several modules, with a contract every interface reads.

**What must be true.** A person's Porelo keeps its organization in the new structure alone: `identity` files through the
store's writes, the Git adapter alone commits and signs, and nothing is left that reads or writes the old registry. A project
reaches a machine through the organization, or stays one registered on that machine alone.

## Plan

Nothing is moved into the old adapter first: what it would move goes at this cut. To be read first: how much of
`IdentityChange` survives as it is.

- **`identity create|rotate|revoke --lost|backup-key`** (`apps/app-vault`) file through `OrganizationWrites` on
  `organization/git-tree`. A card stays signed before it is filed: a change of key takes the old and the new key's signatures
  through the agent. `create` and `rotate` take `--key`, `--id` and `--json`. `--registry` becomes `--organization` and `--project` goes on `backup-key`; `identity show` says
  `organization` for `registry`, its `pending` rows gain the new key's `fingerprint`, and a new `--id` with another key replaces a
  pending change that reached the organization nowhere. `identity revoke --lost` files nothing and answers the new card, which
  an owner files with `org person replace`.
- **What goes:** `registry read|verify|joinable|file`, `identity publish|status`, `project follow|unfollow|verify|access`,
  `project following|refresh`, `RegistryCommit`, the old filing in `RegistryFileCommand` and `IdentityChange`, `organization/git`.
  The daemon's `Following`, `RefreshProjects`, `Follow`, and a project's `following` field.
- **What changes:** `Unfollow` becomes `RemoveProject`, with `porelo project remove`, for a project on this machine alone. `project
  list` lists the organization's projects the person is in, then the machine's own, with `source`. `organization/git-tree` takes
  the adapter's final name.
- **Stay out:** `project.yml`'s writes, which are `project repository`'s.

**Red first:** a test that no class outside the Git adapter runs `git` on the organization's repository, with the allowance of
the boundary test at none; `identity rotate` filing through a stand-in store.

## Acceptance

- `identity create`, `rotate`, `revoke --lost` and `backup-key` make the same refusals and exit codes the contract's section 1 and
  3 name, against a test organization, seen red first where they change.
- A search of the repository finds no reader or writer of the old registry's layout, the search shown to find one first.
- The acceptance suite runs on the new structure, with no step using a command that went.

## To be checked

- Which tests of the old filing carry a guard the new store's checks do not, and move them over before deleting them: signing,
  a change id asked again, a base that moved on, a filed card's signatures.
- What of `SignerHandover`, `ProjectAccess` and the host's reading rests on the old adapter: read before removing it.
