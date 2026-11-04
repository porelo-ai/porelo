# B117 — What Clearing Leaves For A Person

**Status:** Estimated.
**Size:** M - reading the vault's left keys in `app-model`, the clear's listing in `app-project`, a scenario, a doc.

**What must be true.** A person who clears a project or this account learns of every deploy key the vault still holds,
readable project or not, and is told what unreviewed work a clear removes before it is gone. Decided 2026-11-04: no
record; the promise is what the vault still knows, so a key already forgotten is not found again.

## Plan

Read in the code on 2026-11-04:

- **Today's read does not count them.** `LeftDeployKeys.held` and `forget`
  (`apps/app-model/src/main/java/org/fuin/porelo/app/LeftDeployKeys.java`) look up only the entries of the repositories
  a known project names, and a `deploy-` entry only when its `upstream` setting is that repository's. `Clearing.keys`
  (`apps/app-project/src/main/java/org/fuin/porelo/app/Clearing.java`) asks them only for a project it can still read
  (`GateSupport.byName`), and `account` only for projects in the inventory. So an entry of a project it cannot read, or
  of a repository taken out of one, is named by nothing. No deploy-key creation or project unfollow command remains.
- **The clear reads the vault.** A new `LeftDeployKeys.all` lists every `deploy:` and `deploy-` entry the vault holds,
  with forge, repository and fingerprint from the entry. `Clearing.project` lists those of that name, `known` null or
  not, as `FOR_A_PERSON`; `Clearing.account` lists every one. The clear that lists them without `--dry-run` forgets them,
  as it does today. A shut vault is said as it is today (`Clearing.keys`), since nothing can be read.
- **Unreviewed work in the listing.** `ProjectDeletion.Result.unreviewed` already names each ref at the gate, and
  `UnhandedWork` what a task holds (asked while it runs, its note when stopped - as `CheckoutApproval.held` does).
  `Clearing.project` adds one item per ref (kind `work`, `<project>/<task>`) and one per task holding commits or
  changes, before the deletion runs; `account` lists them in the dry run it already makes first.
- **Who lets that work go.** At a terminal, the confirmation that names each `work` item is enough to clear it:
  `ClearCommand.forceAnyway` already asks about refused items, no by default; it must name each work item
  by task and project, and a yes clears without `--force`; `--yes` alone never answers it. Where nobody can be asked -
  no terminal, or
  `--no-input` - a clear that would remove unreviewed work is still refused without `--force`, and names that work.

Steps: `LeftDeployKeys.all` with `LeftDeployKeysTest`; `Clearing` reads it; the work items;
`Result.asMap` keeps its shape (items already have a kind); `doc/commands.md` under `porelo clear`; a scenario in
`acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/project/` clearing an account whose vault holds a left
key of a project it cannot read.

Red first: `ClearingTest` - a vault holding a `deploy:` entry of a project this machine cannot read, then
`account(true, false)`: the result's `keys` is empty today.

## Acceptance

- A vault holds a `deploy:` or `deploy-` entry of a project this machine cannot read, and `porelo clear` runs: the key
  is named `for you` with its forge, repository and fingerprint. Seen to fail: the clear answering no key for it.
- A clear that would remove work waiting at the gate or a task's unpushed work says which, by task and project,
  in the listing before anything is removed. Seen to fail: a listing without `--yes` that does not name it.
- At a terminal, the question that names that work clears it on a yes and keeps it on the default no, without
  `--force`. Without a terminal, `porelo clear --yes` keeps it and names it; with `--force` it clears it. Seen to
  fail: a clear without a terminal and without `--force` that removes unreviewed work.

## To be checked

- A `deploy-` entry could be a person's own, since a vault entry's name is free: whether its value is a stored key and
  its settings hold an `upstream` tells them apart, and what a clear does with one that is neither.
