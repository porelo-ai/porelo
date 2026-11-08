# [B531](README.md#b531) — A Project's Repositories On This Machine

**Status:** Review. Filed 2026-11-08 from the third move's finding 5 (the operator's decision: a machine's project
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
  `carriedHere: false`; a record one commit ahead reads `ahead: 1` and a record of five columns `ahead` absent (no
  measure fills `ahead` yet, see below).
- **Found in the build:** a host file that is no JSON threw `JsonException` out of `OrganizationProjects.read`, which
  caught only `IllegalArgumentException` and `DateTimeParseException`, so one such file failed the whole `Projects()`
  listing; it now reads as "does not read" (`IOException`). `projects()` already read the host's files through
  `hostFiles` for `hosted()`; `names()` never touches them. The plan's "a start refuses" check is
  `HostForgeAddress.refusal`, and with it `CanStart` (`StartCheck`) and a gate command naming `--upstream` refuse alike;
  a host file that does not read refuses the start.
- **Found in the code reading:** a host file this person may not read, or none, refuses a start too (a locked
  member's read is taken off; no file means no copy on the forge). Every gate command builds its gate through
  `GateSupport.gate`, which now reads the same refusal without `--upstream` too: the gate then forwards nothing
  (approve, signed approve, an online pass-on), while what stays on the machine (review, reject, forget) still works
  for a task started before the host stopped carrying it. `SyncUpstream` and the timer only fetch, so they need none.
  A removed project's records are removed under their lock after its mirror and file, and a measure or answer is
  written only while its mirror or file is still there. `central`, `ahead` and the rest show only where `carriedHere`
  is true; rows of a project not taken yet show nothing measured; a taken project whose file is gone lists none. The
  host is asked about every row with `central` and only the carried are recorded; the project's own repository is
  not asked about. A host failure leaves rows with no `central` `no-central`. A host file that does not read still
  fails the listing, said rather than passed over as not carried (an earlier decision, OrgReadingCommandsTest); the
  reading's wish that it hide no other project is left. Readers of the record directory skip its dot files (locks and writes in flight).
- **After the build: the host's central measure.** The person's account holds no credential for `central` on a host,
  so the timer reads the host's follow's last answer instead: `/usr/local/sbin/porelo-host-push --central <project>`,
  taken by that path through `SystemProgram.at` (root's, in directories only root writes), run in the person's account
  through the context's runner, no sudo. One JSON object, `project`, `read_at` (UTC) and `repositories`; each entry's
  `repository` is the forge copy's name, matched without case to the row's `central` last part. Per carried
  repository the record keeps `ahead` (commits for `ahead`, 0 for `up-to-date`), `aheadMeasured` (`read_at`),
  `central` and `centralWhy` in four columns after the gate's five; `behind` stays the gate's measure, and the gate's
  measure (timer and `SyncUpstream`) and the host's answer each write only their own half. A row without `central` is
  `no-central`; an unlisted one `cannot-say`/`unreachable`; `repositories: []` `cannot-say` for each. Anything the
  program may not print refuses the whole answer, and a failed call (any non-zero exit, 69 and 64 included, timeout,
  no program, a program others could write) records `cannot-say` with `host-answer-refused` or `host-command-failed`
  and keeps the earlier `ahead`. A project not taken here yet is not asked: its centrals are in the file a take
  brings.

## Acceptance

- `Projects()` on a host names per repository carried here or not, ahead and behind once measured, and its running
  tasks; off a host `carriedHere` is absent. Seen in a daemon test and once against a VM host.

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
