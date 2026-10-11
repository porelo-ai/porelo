# B170 — A Devcontainer File As Image Source

**Status:** Estimated.
**Size:** M - the image and a Dockerfile build from the mirror; features are not fetched, which leaves out a format
and a registry fetch of their own.

**What must be true.** Where a repository has `.devcontainer/devcontainer.json`, a task's base image and tools can be
taken from it; what Sokar does not take - ports, mounts, privileges - is ignored and said.

## Why

Many repositories already say in that file which image and tools they build with; keeping the same twice in
`project.yml` is work and drifts. Comparable tools read the file.

## The shape

- `project.yml` says `image: devcontainer` (or the path); the base image is taken from the file's `image`, or built
  from its `build.dockerfile` as a second way to it, everything else is listed as ignored at the start and by
  `--dry-run`.
- Features are not fetched: each would run a script from a registry as root at build time. A file that names any is
  still taken, and the start and `--dry-run` name each feature as not installed.
- The image is built outside the task's firewall, as every task image is, and the start says so, naming what the build
  fetched from.

## Plan

Today `image.base_image` is required (`core/.../project/ProjectReader.java`) and becomes the `FROM` line in
`runtime/.../Containerfile.java`; `image.snippet` adds lines run as root. The devcontainer file is read from the
repository's mirror at its default branch - reviewed work only, never what a task pushed and nobody approved; the
review already ranks `.devcontainer/` as dangerous (`gate/.../ReviewRanking.java`).

1. **Red first:** a test in `core` that a project file with `image.devcontainer: .devcontainer/devcontainer.json`
   and no `base_image` is read, and one with both is refused by name.
2. **`image.devcontainer`** in `ProjectReader` and `ProjectSchema`: a path in the repository, `true` for the default
   path; `base_image` becomes required unless it is given.
3. **`core/.../project/DevcontainerFile.java`:** reads the file, which is JSON with comments and trailing commas,
   with a reader of its own (the `wire` `Json` takes strict JSON only). Taken: `image`, or `build.dockerfile` with
   `build.context`, `build.args` and `build.target` (paths relative to the file), and `containerEnv`; a file with
   both `image` and `build` is refused by name. Everything else - `features`, `forwardPorts`, `mounts`, `runArgs`,
   `privileged`, `capAdd`, `securityOpt`, `postCreateCommand` and the rest - is listed as ignored by name, and each
   feature as not installed.
4. **The start and `--dry-run`** (`StartCheck`, `TaskPrepareCommand`) print the image taken, the file it came from
   and each ignored key; `Containerfile` uses the image as `FROM`, as `base_image` is used now.
5. **`build.dockerfile`:** the Dockerfile and its context are taken from the mirror at the default branch, as the
   devcontainer file is, and built by the image build into a base image of the project's own, tagged by the
   commit it came from; that image is then `FROM`. `build.args` are passed, `build.target` is honoured, and a
   Dockerfile that asks for a secret or an SSH mount at build time is refused by name.
6. **Features are not fetched.** Each is a script from a registry run as root at build time; the start and
   `--dry-run` name every feature the file lists as not installed, so a person adds what is needed by
   `image.snippet`.
7. **Docs:** `doc/project-file.md` says the key, what is taken, that features are not installed, and what is
   ignored.
8. **Acceptance:** a scenario in `acceptance/suite/.../project/project-file-check.feature`: a repository whose
   devcontainer file names `ubuntu:24.04` and `forwardPorts: [3000]` gets a task on that image, and the start names
   `forwardPorts` as ignored; a second whose file names `build.dockerfile` and a feature gets a task on the image
   built from it, and the start names the feature as not installed.

## Acceptance

- Seen to fail first, then green: a repository with a devcontainer file naming an image and a port gets a task on that
  image; the port is named as ignored.
- A devcontainer file naming `build.dockerfile` gets a task on the image built from it, from the default branch.
- A feature the file names is not fetched, and the start and `--dry-run` say so by its name.
- `doc/project-file.md` says it.
