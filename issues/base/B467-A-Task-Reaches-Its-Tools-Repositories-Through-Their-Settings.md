# B467 — A Task Reaches Its Tool's Repositories Through Their Settings

**Status:** Implementing; blocked by [B468](README.md#b468) for the adapter's own settings.
**Size:** M - a section of the project file with its schema and document, the call at both start paths, the draft of a
first start, and their tests; no change to Porelo's own daemon contract.

**What must be true.** A project declares, in `project.yml`, the repositories its build tool reaches, each by its name
and URL, and may keep settings for the tool's adapter beside them. When the project's task starts, it holds the settings files that the installed
ecosystem adapter writes for those repositories, so a build in the task asks only them.

## Found

An ecosystem adapter answers `Configure(repositories)` with the files a tool's settings need: for Maven, a
`settings.xml` with a mirror per repository. The adapter documentation says that Porelo places these files. Read in the
code, nothing in Porelo calls `Configure`: of the tracked Java files, only the contract library, its stub and the tests'
stand-ins name it. `project.yml` has no place for a tool's repositories either. A repository manager that stands in for
every repository, such as one at `repo.example.org` taking `*`, therefore never reaches a task's settings. Unless the
image itself carries settings, a build in the task asks the tool's default hosts, and the egress refuses them.

## Plan

- **The project file.** Two new optional top-level sections. `tool_repositories` is a list of entries with `ecosystem`
  (the adapter's name, as `porelo ecosystems` lists it), `name` and `url`; `extensions` maps an adapter's name to a block
  Porelo never reads. Read into the model (`ProjectFileReader`, `ProjectReader`, `Project`, `ProjectSchema`) and refused
  when read: a name outside `[A-Za-z0-9_-]` (a dot would split the key that names it in a block), two entries of one name, a URL that is not `https` (or plain `http`
  on a literal loopback address), a URL whose host the project's `egress` does not name - the refusal names the line to
  add - and a block that is no mapping or over the contract's size or depth. Egress and every later credential binding
  derive from `name` and `url` alone, never from a block. An offline project declares none. `doc/project-file.md` shows
  both once.
- **The call.** In `TaskLaunch`, before the container is created, each ecosystem the section names is found among the
  installed adapters (`InstalledEcosystems`) and asked `Configure` with its entries, inside the task the URL as written, and with its block from `extensions`.
  A missing adapter, a refusal, a failure or a timeout stops the start, naming the adapter and what the person does,
  never the adapter's words; nothing is created then.
- **The files.** Each returned file is placed under the task user's home (`/home/agent/<path>`) with `TaskRunner.place`,
  owner-only where the adapter says, on the paths a new container takes, beside the agent's files; a resumed container
  keeps what it holds. Two adapters answering one path stop the start.
- **The draft.** A first start's draft writes a `tool_repositories` entry from each finding keyed `repository.<name>`,
  its value the URL, with the host beside it in `egress`; and `extensions.<adapter>` from each finding keyed
  `extension.<key>`, the key's dots nesting and the value text, so the person's own settings carry into the task.
- **Credentials** are not served here: a repository that wants one keeps to what its settings say, and the broker's
  part follows in an issue of its own.
- **Red first:** `ProjectReaderTest` reads the section and refuses each bad entry; a `TaskLaunch` test with a fake
  adapter places its settings file in the task user's home and stops the start when the adapter refuses; a
  `ProjectProposal` test writes both sections from a `repository.corp` finding and an `extension.mirrorOf.corp` finding.

## Acceptance

- A project that declares a repository by name and URL, with `mirrorOf: "*"` kept for the Maven adapter, starts a task
  whose tool settings hold that one mirror. A build there resolves an artifact only that repository holds, and one of the tool's default
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
- How a repository that wants a username and a password is served: the broker sends one stored value after a prefix,
  so today such a credential is stored as the Base64 of `username:password` behind `Basic `; whether the broker should
  compose the two itself.
