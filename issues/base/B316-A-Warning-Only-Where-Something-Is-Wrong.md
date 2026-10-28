# B316 — A Warning Only Where Something Is Wrong

**Status:** Analyzed
**Size:** M - one check in `vault put` that knows what the followed projects ask for, and a sweep of every warning
the command line prints.

**What must be true.** Porelo warns only where something is wrong or about to fail, and then names what to do; a
name, a state or a setting that is in fact fine is said as what it is for, or not at all.

## Plan

Read in the code on 2026-10-28.

- **The case seen:** `porelo vault put github-actions` warns "no provider 'github-actions' is declared … a task will
  only find this credential if something asks for it by that name", although a followed project's
  `builds.credential: github-actions` asks for exactly that name: the task's builds helper reads it from the vault
  (`BuildsWiring`, `TaskWatchBuildsCommand`). No provider of that name is planned: providers are the model
  endpoints, and a build reader's token is not one.
- `VaultPutCommand.warnIfNothingWillUseIt` already stays silent for a name a credential declaration reads
  (`useOf`, printing `used for …`) and for git's own names (`GitCredentialNames.isOne`). It does not know the names
  the followed project files ask for: `builds.credential`, and the vault names under a project's `credentials:`.
- **Step 1:** `useOf` also takes those names from every followed project (the same read `ProjectInventory` makes of
  `credentials:`), so the entry is answered `used for sluice-dry's build reader` and no warning follows. A name
  nothing asks for yet keeps one line, which says what may ask for it ("a provider, a project's `builds.credential`
  or `credentials`, or a declaration") instead of implying a mistake, since a credential is commonly stored before
  the project that needs it is followed.
- **Step 2, the sweep:** the command line's warnings are 263 `err.println("porelo: …")` lines in 79 files of
  `apps/*/src/main` (counted). Each is read for the same kind: a warning for a state that is fine, or one that names
  no action the person can take. Seen so far as candidates: `agents --verbose`'s "waiting: not declared - whether
  it waits for a person cannot be told" (the person cannot act; the agent's package can), `doctor`'s "daemon
  dumpable no … its /proc entries belong to root" (a hardening that holds, printed like a finding). Each found is
  changed to say what it is, or removed; the list goes into this issue as it is read.
- **The test that goes red first:** in `VaultPutCommandTest`, a followed project whose `builds.credential` names the
  entry: `vault put` prints `used for` that project and no `porelo:` line (red today). The harm it guards: a person
  told a stored credential will not be found removes it, and the task's build reader then fails later. The sweep's
  rewordings get no test, by the rule that what only reads wrong is fixed, not guarded.

## Acceptance

- `porelo vault put github-actions` with `sluice-dry` followed prints `used for` it and no warning; without any
  project asking for the name, one line that says what may ask for it.
- The sweep's list in this issue, each entry changed or kept with its reason.

## To be checked

- Whether a room login stored by `porelo talk join --into-vault` and the agents' sign-ins reach `vault put`'s check
  at all, or are written by their own commands without it.
