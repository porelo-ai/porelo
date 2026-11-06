# B467 — A Task Reaches Its Tool's Repositories Through Their Settings

**Status:** Idea.
**Size:** -

**What must be true.** A project declares, in `project.yml`, the repositories its build tool reaches, each with its name,
its URL and what it stands in for. When the project's task starts, it holds the settings files that the installed
ecosystem adapter writes for those repositories, so a build in the task asks only them.

## Found

An ecosystem adapter answers `Configure(repositories)` with the files a tool's settings need: for Maven, a
`settings.xml` with a mirror per repository. The adapter documentation says that Porelo places these files. Read in the
code, nothing in Porelo calls `Configure`: of the tracked Java files, only the contract library, its stub and the tests'
stand-ins name it. `project.yml` has no place for a tool's repositories either. A repository manager that stands in for
every repository, such as one at `repo.example.org` taking `*`, therefore never reaches a task's settings. Unless the
image itself carries settings, a build in the task asks the tool's default hosts, and the egress refuses them.

## Acceptance

- A project that declares a repository with a name, a URL and a `*` it stands in for starts a task whose tool settings
  hold that one mirror. A build there resolves an artifact only that repository holds, and one of the tool's default
  repository, both through the declared host, and asks no other host.
- A project that declares none starts a task as today, with no settings file placed.
- A returned file is placed only inside the task user's home, readable by that user alone where the adapter says so; a
  path the contract does not allow refuses the start and is never written.
- An adapter that refuses or fails `Configure` stops the start with a refusal that names the adapter and what the person
  does, never with the adapter's own words.

## To be checked

- Which URL a declared repository has inside the task: its own, reached through the egress, or one the broker serves
  so that a credential stays on the host.
- Where in `project.yml` the repositories go, and whether a first start's draft fills them from what detection found.
