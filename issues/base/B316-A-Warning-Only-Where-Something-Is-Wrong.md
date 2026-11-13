# B316 — A Warning Only Where Something Is Wrong

```json
{
  "$schema": "issue-1.json",
  "id": "B316",
  "title": "A Warning Only Where Something Is Wrong",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 49,
  "blockedBy": [],
  "covers": "Porelo warns only where something is wrong: `vault put` knows the names placed projects ask for (`builds.credential`, `credentials:`) and says what an entry is used for; a sweep of the command line's 263 warnings for fine states printed as findings"
}
```

**Why this size.** one check in `vault put` that knows what the followed projects ask for, and a sweep of every warning
the command line prints.

**What must be true.** Porelo warns only where something is wrong or about to fail, and then names what to do; a
name, a state or a setting that is in fact fine is said as what it is for, or not at all.

## Plan

Read in the code on 2026-11-04.

- **The case seen:** `porelo vault put github-actions` warns "no provider 'github-actions' is declared … a task will
  only find this credential if something asks for it by that name", although an organization project's
  `builds.credential: github-actions` asks for exactly that name: the task's builds helper reads it from the vault
  (`BuildsWiring`, `TaskWatchBuildsCommand`). No provider of that name is planned: providers are the model
  endpoints, and a build reader's token is not one.
- `VaultPutCommand.warnIfNothingWillUseIt` already stays silent for a name a credential declaration reads
  (`useOf`, printing `used for …`) and for git's own names (`GitCredentialNames.isOne`). It does not know the names
  the placed project files ask for: `builds.credential`, and the vault names under a project's `credentials:`.
- **Step 1:** `useOf` also takes those names from every placed project (`OrganizationProjects` records the placements,
  and `ProjectInventory` reads their `credentials:`), so the entry is answered `used for sluice-dry's build reader` and
  no warning follows. A name nothing asks for yet keeps one line, which says what may ask for it ("a provider, a
  project's `builds.credential`
  or `credentials`, or a declaration") instead of implying a mistake, since a credential is commonly stored before
  the project that needs it is placed.
- **Step 2, the sweep:** the command line's warnings are 263 `err.println("porelo: …")` lines in 79 files of
  `apps/*/src/main` (counted). Each is read for the same kind: a warning for a state that is fine, or one that names
  no action the person can take. Seen so far as candidates: `agents --verbose`'s "waiting: not declared - whether
  it waits for a person cannot be told" (the person cannot act; the agent's package can), `doctor`'s "daemon
  dumpable no … its /proc entries belong to root" (a hardening that holds, printed like a finding). Each found is
  changed to say what it is, or removed; the list goes into this issue as it is read.
- **The test that goes red first:** in `VaultPutCommandTest`, a placed project whose `builds.credential` names the
  entry: `vault put` prints `used for` that project and no `porelo:` line (red today). The harm it guards: a person
  told a stored credential will not be found removes it, and the task's build reader then fails later. The sweep's
  rewordings get no test, by the rule that what only reads wrong is fixed, not guarded.

## Acceptance

- `porelo vault put github-actions` with `sluice-dry` placed prints `used for` it and no warning; without any
  project asking for the name, one line that says what may ask for it.
- The sweep's list in this issue, each entry changed or kept with its reason.

## To be checked

- Whether a room login stored by `porelo talk join --into-vault` and the agents' sign-ins reach `vault put`'s check
  at all, or are written by their own commands without it.

## Built (2026-11-13)

- **Step 1.** `VaultPutCommand.useOf` takes what the projects here ask for besides the declarations
  (`askedByProjects`): for every project `ProjectSource` names, apart from `default`, the entries under its
  `credentials:` (`<project>'s <destination>`) and its `builds.credential`, its own file's or else its host's, as a
  task wires them (`<project>'s build reader`). `vault put github-actions` with `sluice-dry` placed prints
  `used for  sluice-dry's build reader` and no warning. A name nothing asks for yet is no longer a warning: one line
  on standard output, `used for  nothing yet - a provider of that name (declared: …), a project's builds.credential
  or credentials, or a declaration with --vault <name> may ask for it`. An agent's name stays a warning, as it is a
  mistake.
- **Red first:** `VaultPutCommandTest.anEntryAPlacedProjectsBuildsReadIsSaidToBeUsedThere` failed before the change
  before the change: standard output held only `stored    github-actions (token, 29 characters)`, no `used for` line, and the warning went to standard error.

### The sweep

Read again: 635 `err.println` lines whose text starts with `porelo: ` in 116 files of
`apps/*/src/main` (the plan's count was 263 in 79). 397 of them are followed at once by a non-zero exit or a
throw: errors that end a command, not touched. The other 238 were read one by one; 224 of them are
right as they are, a failure or a refusal that names its cause, and are not listed.

Changed:

- `apps/app-vault/.../VaultPutCommand.java` (`unknownName`, was line 462): "no provider 'x' is declared … a task will
  only find this credential if something asks for it by that name" for a name nothing asks for yet, a fine state
  said as a finding, is now the `used for  nothing yet - …` line above, on standard output without `porelo:`.
- `apps/app-project/.../ProjectRemoveCommand.java:92`: "porelo: removing <name>, its transports in their turn" is
  progress, not a warning: `removing  <name>, its transports in their turn`, on standard error still.
- `apps/app-vault/.../HostGatherFlow.java:599`: "porelo: left <path> behind" named no action: "…; remove it".
- `apps/app-agents/.../AgentsCommand.java:205,220` (standard output, named by the plan): `agents --verbose`'s
  "not declared - whether it waits for a person cannot be told", and the same of `ready`, said what the person cannot
  act on as a finding: "not declared in the agent's package, so Porelo does not tell when it waits for a person" (and
  "… reached work").
- `apps/app/.../DaemonHardening.java:93` (standard output, `doctor`, named by the plan): "daemon dumpable no - pid N,
  its /proc entries belong to root", a hardening that holds printed like a finding: "no, as it should be - pid N;
  its /proc entries belong to root, so no process of this account can dump or trace it to read the vault's secrets".

Kept, with the reason:

- `apps/app-vault/.../LoginRenewal.java:215`, "porelo: renewed the sign-in for …": a fine state, but written to the
  broker's log, where [credentials](../../doc/credentials.md) tells a person to look for exactly that line.
- `apps/app-task/.../TaskWatchBuildsCommand.java:178`, "porelo: the forge answers": the builds helper's log, taking
  back the trouble line it wrote before; the task is told the same through its files.
- `apps/app-task/.../TaskCertifyCommand.java:254`, "a list will not say the new end": the certificate holds; the line
  says which view stays behind, and there is nothing more to do than certify again.
- `apps/app-vault/.../IdentityBackupKeyCommand.java:167`, `apps/app-base/.../Suggests.java:47,50`,
  `apps/app-task/.../TaskLaunch.java:2371`: the head of a refusal or of a list that follows one.
- `apps/app-messaging/.../TransportLifecycle.java:161`: a transport's own words, relayed at a terminal.
- `apps/app-egress/.../ShieldEgressCommand.java:225,289,394`: the change holds for the running task only, and the
  line says why it cannot be written to the project file.

Removed: none. Of the 238, 3 changed, 11 kept with the reasons above, 224 right; the two named by the plan were on standard output and are changed besides.

### To be checked, answered from the code

- **`porelo talk join --into-vault` does not go through `vault put`'s check.** `TalkJoinCommand.keep` writes the
  login with `context.vault().update` itself, so nothing says what reads it.
- **The agents' sign-ins do not either.** `vault login` (`AgentLogin`) and `vault authorize` (`VaultAuthorizeCommand`)
  write their entries with `context.vault().update`, and so does `vault import` (`CredentialImport`). Only where a sign-in turns out to be a key do both offer to run
  `porelo vault put <entry>` as its own process, and that one is checked. `host stop-secrets` uses `vault put`'s
  `replace` and `converted`, not its check.
