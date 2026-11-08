# [B494](README.md#b494) — Build Status Reads The Builds A Host Names

**Status:** Implementing.
**Size:** S - one place in `BuildStatus` and its unit test.

**What must be true.** On a host, `porelo build status -p <project> -r <repository>` answers a project the host carries from
the builds the host's file names for it, as the task's builds watch does, when the project's own `project.yml` names no
`builds:`. A project file's own `builds:` still wins, and a project nobody names builds for still answers 78.

## Plan

- `BuildStatus.read` takes `project.builds()`, and where that is null, the placed record's builds
  (`OrganizationProjects.Placed.builds()`), the same choice `BuildsWiring` makes at a task's start.
- Red first: a unit test with a placed record that names builds and a project file without them, which answers 78 today.

## Acceptance

- The test above answers the host's builds; a project file's own `builds:` still wins; no builds anywhere still answers 78.
