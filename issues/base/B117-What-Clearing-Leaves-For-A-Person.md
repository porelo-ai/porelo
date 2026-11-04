# B117 — What Clearing Leaves For A Person

**Status:** Estimated.
**Size:** M - a record of handed-out keys in `app-model`, the clear's listing in `app-project`, a scenario, a doc.

**What must be true.** A person who clears a project or this account learns of every deploy key this machine ever
handed to a forge for it, including a project that was unfollowed before, and is told what unreviewed work a
clear removes before it is gone.

## Plan

Read in the code on 2026-11-04:

- **Why a key is lost today.** `LeftDeployKeys.forget`
  (`apps/app-model/src/main/java/org/fuin/porelo/app/LeftDeployKeys.java`) drops a legacy key from the vault and
  returns it once; nothing keeps that return. `Clearing.keys`
  (`apps/app-project/src/main/java/org/fuin/porelo/app/Clearing.java`) asks `LeftDeployKeys.held` or
  `forget` only for a project it can still read (`GateSupport.byName`), so it cannot name keys of a missing project.
  No deploy-key creation or project unfollow command remains; no record keeps every key handed out before.
- **The machine keeps a record.** The interface cannot find them by title alone: it reaches only the repositories a
  person's sign-in reaches, and needs the forge of each. The required record holds every handed-out key - project,
  repository, upstream, fingerprint, title - in `deploy-keys.json` in the state directory, owner-only;
  `LeftDeployKeys.forget` marks the line forgotten here instead of dropping it. Keys made before the record are found
  by nothing; said once in
  `doc/commands.md`.
- **The clear reads the record.** `Clearing.project` lists every line of the record for that name, followed or not,
  as `FOR_A_PERSON` with forge, repository and fingerprint, even when `known` is null; `Clearing.account` lists every
  line. The clear that lists them without `--dry-run` drops those lines, as it drops the vault entries today: it is the
  last thing that knows them.
- **Unreviewed work in the listing.** `ProjectDeletion.Result.unreviewed` already names each ref at the gate, and
  `UnhandedWork` what a task holds (asked while it runs, its note when stopped - as `CheckoutApproval.held` does).
  `Clearing.project` adds one item per ref (kind `work`, `<project>/<task>`) and one per task holding commits or
  changes, before the deletion runs; `account` lists them in the dry run it already makes first.
- **Who lets that work go.** At a terminal, the confirmation that names each `work` item is enough to clear it:
  `ClearCommand.forceAnyway` already asks about refused items, no by default; it must name each work item
  by task and project, and a yes clears without `--force`; `--yes` alone never answers it. Where nobody can be asked -
  no terminal, or
  `--no-input` - a clear that would remove unreviewed work is still refused without `--force`, and names that work.

Steps: the record beside `LeftDeployKeys` with `LeftDeployKeysTest`; `Clearing` reads it; the work items;
`Result.asMap` keeps its shape (items already have a kind); `doc/commands.md` under `porelo clear`; a scenario in
`acceptance/suite/src/test/resources/org/fuin/porelo/acceptance/project/` following, keying, unfollowing and
clearing a project.

Red first: `ClearingTest` - a project given a key, then unfollowed, then `account(true, false)`: the result's `keys`
is empty today.

## Acceptance

- A project is followed, given a deploy key, unfollowed, and then `porelo clear` runs: the key is named `for you`
  with its forge, repository and fingerprint. Seen to fail: the clear answering no key for that project.
- A clear that would remove work waiting at the gate or a task's unpushed work says which, by task and project,
  in the listing before anything is removed. Seen to fail: a listing without `--yes` that does not name it.
- At a terminal, the question that names that work clears it on a yes and keeps it on the default no, without
  `--force`. Without a terminal, `porelo clear --yes` keeps it and names it; with `--force` it clears it. Seen to
  fail: a clear without a terminal and without `--force` that removes unreviewed work.
