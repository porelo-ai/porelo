# [B493](README.md#b493) — A Host Task Reads The Builds Its Host Names

**Status:** Review; built on the operator's word that every finding of the trial move is fixed before the second
move, with the design the operator chose: the host's file names the builds, and the project stays as it is.
**Size:** M - a field read from the host's file, the start's builds wiring for a guarded project, `doc/reach.md`
and their tests; the host's side is another repository's.

**What must be true.** A task on a host, of a project the host carries, is told what its builds did on the host's
own forge - each verdict and the logs of a failed job in `/porelo/files`, as an online task with `builds:` is today -
when the host's file names the builds for that project, even though the project is `guarded` and its `project.yml`
names no `builds:`. `project.yml` stays the same on every machine; nothing host-internal goes into it.

## Found

- **Seen during the trial move.** The moved task of porelo, a guarded project with no `builds:`, was never told its
  builds on the host's forge, so the person and the agent read them in the browser.
- `BuildsWiring.start` returns at once unless `project.builds()` is set and the project is `online`.
- The host already names, per project it carries, what a machine cannot know from `project.yml`: `fetch_from`,
  `homeserver` and `room`, read into `OrganizationProjects.Placed` and joined in `OrganizationProjects.find`.

## Plan

- `OrganizationProjects.Placed` gains an optional `builds`, read from the host's file for the project in the same
  shape as `project.yml`'s `builds:` block (forge, api, credential, logs), validated as that block is; `find` carries
  it from the host's file. The host's side writes it, and the credential it names is the person's own forge entry on
  the host.
- On a host, the start takes the builds from `project.yml` where it names them, else from the host's file, and the
  builds helper runs beside the task for a guarded project too when they came from the host's file. Without either,
  nothing changes.
- `doc/reach.md` says that a guarded task on a host is told its builds from the host's own forge when the host names
  them, as a control or an accident-catcher, as `ReachDocumentTest` holds every row to.
- **Red first:** a start test on a host stand-in whose host file names builds for a guarded project asserts that the
  builds helper is started with the host's forge, API and credential; and that with no builds in the host's file, or
  off a host, no helper starts.

## Acceptance

- The test above fails on today's start and passes after; the existing builds tests of an online project still pass.
- `ReachDocumentTest` passes with the new row.

## Read while building

- **What the helper watches on a host:** the task's settled branch, `porelo/<task>` by default, at the repository's
  address as this machine reaches it, which on a host is its own forge (`HostForgeAddress`). A push approved onto
  another branch is outside that watch.
- **A missing credential:** with the host's builds and no entry of that name in the person's vault, the start says
  so, names `porelo-host-forge-token`, starts the task, and starts no helper.
- **A guarded task's helper runs with `--builds-only`** and opens no pull request; the gate's approval does that.

## Left by the reading, gathered in [B380](README.md#b380)

- **A start with a locked vault is not told the host's command at once.** When nothing else in the start opens the
  vault, the start cannot see whether it holds the entry, so the helper starts and waits for the vault; if the entry
  is then missing, the helper's own record says to store it with `porelo vault put`. The start opens a shut vault
  when its agent takes a credential from it (`TaskLaunch`, `declaredOrAdded`), as `claude` in both moves does, so
  the case needs an agent that takes none on a host with a vault left locked; the task's work is unaffected, and the helper's words name the missing entry.
