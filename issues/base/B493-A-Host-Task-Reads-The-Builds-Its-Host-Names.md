# [B493](README.md#b493) — A Host Task Reads The Builds Its Host Names

**Status:** Implementing; built on the operator's word that every finding of the trial move is fixed before the
second move, with the design the operator chose: the host's file names the builds, and the project stays as it is.
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

## To be checked

- Which upstream and branch the helper watches for a host task: whether a host's taking makes the repository's
  upstream the host's forge, so the helper asks the forge that holds the task's branch.
