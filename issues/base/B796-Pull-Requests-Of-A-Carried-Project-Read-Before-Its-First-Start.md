# B796 — Pull Requests of a Carried Project Read Before Its First Start

```json
{
  "$schema": "issue-1.json",
  "id": "B796",
  "title": "Pull Requests of a Carried Project Read Before Its First Start",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 138,
  "blockedBy": [],
  "covers": "WatchPullRequests and PullRequests read the pull requests of a project the host carries for the person before a start has placed it there, from the host's record of the project and its carried repositories, instead of refusing it as a project this machine does not have"
}
```

**Why this size.** S: one module (`app-model`). `HostPullRequestReader` takes the repositories of a carried, unplaced
project from the host's record, and the daemon's two methods resolve the project that way when `ProjectSource` finds no
file. A test for each.

**What must be true.** On a rented host, a person whose project the host carries sees that project's open pull
requests before they start any work there. The machine never answers "no project here" for a project its own
`porelo project list` shows.

## Found

On 2026-11-17, in a live test on a rented host. The host carried `porelo` with three repositories, and
`porelo project list` on it listed `porelo`. No start had placed the project for the person yet, so the person's data
directory held only the vault. The daemon's `WatchPullRequests` with `{"project":"porelo"}` answered at once:
`Failed: no project 'porelo' here. This machine has: default`. The window asked again every few seconds and kept the
item in *Needs you*.

## Plan

- Today: `GateMethods` (`daemon`) resolves `PullRequests` and `WatchPullRequests` through `GateSupport.byName`, which is
  `ProjectReader.read(ProjectSource.require(...))`. `ProjectSource.resolve` and `names()` count only the built-in
  project, projects a start placed (`OrganizationProjects.find` and `names()` read the placed directory), written
  projects and registered projects. A project carried only by the host, in `/etc/porelo-host/organization/projects/<name>.json`
  (`OrganizationProjects.host`), is not among them. `ProjectListCommand` does list it, through `carried(...)`.
- Change: in both methods, when `ProjectSource.resolve` finds no file and `OrganizationProjects.host(name)` carries the
  project for this person, `HostPullRequestReader` polls the repositories the host's record names (its
  `repositories()` and the forge settings). It needs no `project.yml` for that. A project neither placed nor carried
  is refused as today.
- `ProjectSource.names()`, which the refusal text prints, also lists the carried projects, so a refusal names what
  `porelo project list` shows.
- Red first: a `HostPullRequestReaderTest`, or a daemon method test, with a host record that carries a project and
  nothing placed. Today it is refused with "no project"; after the change it answers the open pull requests of the
  carried repositories.

## Acceptance

- On a host that carries a project which no start has placed for the person, `WatchPullRequests` answers its
  repositories' open pull requests, and the window shows no "cannot be read" item for it.
- A project that is neither placed nor carried is still refused, and the refusal names the carried projects too.
- The test above fails without the change.
