# [B531](README.md#b531) — A Project's Repositories On This Machine

**Status:** Backlog. Filed 2026-11-08 from the third move's finding 5 (the operator's decision: a machine's project
page shows the project's repositories on that machine).
**Size:** L - three fields on a contract type, a second measure on a host (its forge's copy against central), the
upstream record grown by a column; no new method.

**What must be true.** A client asking a machine for its projects learns, per repository of a project, whether this
machine carries it, holds work of it, how its mirror stands against the central forge, and how many of its tasks run
here, without asking anything else or reaching the network.

## Plan

- Today: `Projects()`/`WatchProjects()` (20-project.varlink, `ProjectMethods`, rows by `PoreloDaemon.projectRow` from
  `ProjectInventory.projects()`) give each project `carried`, `tasks`, `running` and `repositories: []Repository`.
  `Repository` has `pending`, `behind` and its reasons, but no carried flag, no "ahead" and no task count.
  `ProjectInventory.repositoriesOf` lists every repository of the project file, whatever the host carries;
  `OrganizationProjects.Placed.repositories` says which a host carries (empty meaning the whole project). There is no
  `org status` command.
- **Fields added to `Repository`** (two edits each, the record and the contract):
  - `carriedHere: ?bool`: true where this machine is the host that carries the repository (`Placed.repositories`
    empty or naming it), false where it is a host of the project that does not carry it, absent off a host.
  - `ahead: ?int` with `aheadMeasured`: commits the mirror's main has that the central branch lacks, read from the same
    upstream record `UpstreamDistance` writes (measured beside `behind` in `measure`, never at listing time, since a
    listing never reaches the network). Up to date is `behind == 0 && ahead == 0`, said by the client.
  - `running: ?int` and `tasks: ?int`: the project's tasks of that repository from `TaskInventory`, counted where the
    project's are counted (`ProjectInventory` 419-437).
- "Held" is `pending > 0` (work at the gate not yet passed on), which the row already has; no new field.
- Red first: `ProjectInventoryTest` with a host file carrying two of three repositories: the third reads
  `carriedHere: false`; a mirror one commit ahead reads `ahead: 1` after a measure and `ahead` absent before.

## Acceptance

- `Projects()` on a host names per repository carried here or not, ahead and behind once measured, and its running
  tasks; off a host `carriedHere` is absent. Seen in a daemon test and once against a VM host.

## To be checked

- That `UpstreamDistance.measure` can count ahead in the same fetch (`git rev-list --count central..main`) without a
  second network call.

## After the plan reading

The build takes these answers; each harm gets a test seen red first.

- **What ahead and behind measure.** `UpstreamDistance.measure` fetches `origin` into `FETCH_HEAD` and counts
  `HEAD..FETCH_HEAD`; approved work goes to the upstream, never into the mirror, and on a host the upstream is the
  host's own forge (`HostForgeAddress.placed`). So on a host the person's "central state" is a second measure: the host
  forge's copy of the repository against its `central`, `ahead` = commits the forge's default branch has that central
  lacks, `behind` the other way, made by the same timer, never at listing time. Off a host `ahead` stays absent.
- **`carriedHere`** is read from `OrganizationProjects.host(name)` alone (the host's file, through `ProjectInventory`'s
  own `hostFiles`, which `projects()` must use too), true only where the host carries the project, the repository is
  named or the carry is whole, and `HostForgeAddress.copyOf` resolves its forge copy; absent where `host(name)` is
  none or does not read. The project's own repository on a project of several reads `carriedHere` as its host carries
  the project. And a start refuses a repository the host's file does not carry (`HostForgeAddress.refusal`), which it
  does not today.
- **Before a project is taken, or a mirror made,** the rows come from `Placed.repositories` with nothing measured, so a
  host shows what it carries at once.
- **The record:** `ahead` is a sixth, optional column of `UpstreamRecords`; a line of five reads `ahead` absent, never 0.
  `SyncUpstream` writes it too.
- **The counts:** `tasks: int` and `running: int` on `Repository`, from the task list `projects()` already read (no
  second listing); a task with no repository counts for the project's own repository; a task of a repository no
  longer in the file stays counted on the project alone. `aheadMeasured: ?string` like `behindMeasured`.
- The record behind the contract's `Repository` is `ProjectInventory.RepositorySummary` (`asMap`), not the core model.
