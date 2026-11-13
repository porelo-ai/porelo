# B727 — A Repository The First Start Found Reaches The Task's Tool Settings

```json
{
  "$schema": "issue-1.json",
  "id": "B727",
  "title": "A Repository The First Start Found Reaches The Task's Tool Settings",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 8,
  "blockedBy": [],
  "covers": "A tool repository the first start's draft names, and its `extensions.maven` block, once the person takes the draft, reach the adapter's Configure at the start in `default`, so the task holds the settings file with the broker's mirror, as the egress host already reaches its egress.",
  "openQuestions": 0
}
```

**Why this size.** One path from the taken draft to the start's tool settings, a stand-in adapter and a test through the first start. Estimate: 45 minutes (Core's median for S).

Found on snapshot.512 in the project `default`: the host of a private repository from the person's Maven settings reached the task's egress, but the task held no `.m2/settings.xml`. The adapter, measured: Detect gives `repository.inhouse`, and Configure answers the settings file with a broker mirror and no credential. Read at 177f3cae2: `ToolSettings.ask` returns no file when `project.toolRepositories()` is empty, so the draft's `tool_repositories` entries (`ProjectProposal`) do not reach the project the start uses in `default`, while the egress host does.

## Plan

- When the person takes the first start's draft, its `tool_repositories` entries become the started project's tool repositories, as its egress hosts become its egress.
- The start then asks the adapter's Configure for them as for a project that declares them, and writes the file it answers into the task.
- The draft's `extensions.maven` block reaches Configure at the start by the same path, as `mirrorOf` already does from a project file. Ecosystems' EC26 (High, built together with this one) reads `extensions.maven.ownSettings` there and then answers no file. No contract change.
- No credential enters the task: the file names the broker's mirror only.

## Acceptance

- Red first, in the JVM, through FirstStart and the start in `default`, with a stand-in adapter answering `repository.inhouse`: after the draft is taken, the task's files hold `.m2/settings.xml` from Configure.
- The file names the broker's address and no password.
- A draft whose `extensions.maven` block is taken hands that block to Configure: with `ownSettings` the stand-in answers no file, and none is written.
- A draft without `tool_repositories` starts as today.

## Done

- **Why it was lost.** `FirstStart.repositoryDraft` wrote the taken draft as the repository's `porelo.yml` through the
  twelve-part `ProjectFile`, which keeps image, build, egress and the rest but no `tool_repositories` and no
  `extensions`. It now writes both. A repository's `porelo.yml` may carry them: `RepositorySettings` merges them onto
  the project, and the start's own settings in `default` hand that project to `ToolSettings.ask`, which asks the
  adapter's `Configure` and writes what it answers into the task.
- **Through the broker or not.** A `repository.<name>` for which `Detect` also found
  `credential.<name>` names that credential in the draft, when the name is a vault entry's and the URL one the broker
  can hold. `ToolSettings.ask` then gives `Configure` the broker's address, and the broker adds the credential from
  the vault. A repository without one keeps its own URL. Neither file holds a password.
- **The block.** The draft's `extensions.<adapter>` block reaches `Configure` by the same path; with `ownSettings`
  the stand-in answers no file, and none is written.
- **Red first.** In `FirstStartTest`, through a terminal yes, the start's own settings in `default` and
  `ToolSettings.ask` with a stand-in maven adapter: `aRepositoryTheDraftNamesReachesTheStartsToolSettings`,
  `theDraftsBlockForAnAdapterReachesItsConfigure` and `aRepositoryWithoutACredentialIsReachedAtItsOwnAddress` failed
  at main. With `repositoryDraft` fixed alone, the first still failed: the adapter got the repository's own URL.
- **Docs.** `doc/ecosystem-adapters.md` says when a draft's repository names its credential and that a start asks
  `Configure` for it once the draft is taken. Changelog under *Fixed*.
- A draft without `tool_repositories` starts as before: `FirstStartTest`'s other 91 cases are unchanged and pass.
- **A credential only where the person's files bind it.** A credential is bound only when a finding outside the
  checkout names the repository at that address, and only when the same adapter found the credential. A checkout
  naming a repository like one the person holds a credential for would otherwise send their secret, through the
  broker, to an address of its choosing. A credential the broker cannot serve, such as an upper-case name or an
  address with a query, is said in the question, and the repository keeps its own address.
  `aRepositoryOnlyTheCheckoutNamesNeverTakesThePersonsCredential`, `anotherAdaptersCredentialOfTheSameNameIsNotTaken`
  and `aCredentialTheBrokerCannotServeIsSaidAndTheRepositoryKeepsItsAddress` in `ProjectProposalTest` failed before.
- **Kept.** The repository's host stays in the draft's `egress`, as `Detect` names it with its own `host.` finding
  too. The task can reach it there, but without the credential: the broker adds nothing the task could not already
  ask for.
- **Locally.** apps/app (4728 on the first commit, 4732 with the binding narrowed) passed whole, and so did the
  root's `validate` and `-Pdocuments test`.
