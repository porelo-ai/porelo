# B170 — A Devcontainer File As Image Source

**Status:** Analyzed.
**Size:** L - the image alone is a day; the features that install tools are a format and a registry fetch of their
own, and which of them to take is to be decided first.

**What must be true.** Where a repository has `.devcontainer/devcontainer.json`, a task's base image and tools can be
taken from it; what Sokar does not take - ports, mounts, privileges - is ignored and said.

## Why

Many repositories already say in that file which image and tools they build with; keeping the same twice in
`project.yml` is work and drifts. Comparable tools read the file.

## The shape

- `project.yml` says `image: devcontainer` (or the path); the base image and the features that install tools are taken,
  everything else is listed as ignored at the start and by `--dry-run`.
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
   with a reader of its own (the `wire` `Json` takes strict JSON only). Taken in the first step: `image`, and
   `containerEnv`. Everything else - `build`, `features`, `forwardPorts`, `mounts`, `runArgs`, `privileged`,
   `capAdd`, `securityOpt`, `postCreateCommand` and the rest - is listed as ignored by name.
4. **The start and `--dry-run`** (`StartCheck`, `TaskPrepareCommand`) print the image taken, the file it came from
   and each ignored key; `Containerfile` uses the image as `FROM`, as `base_image` is used now.
5. **Second step, features:** a feature is an OCI artifact holding `install.sh` and its options. Fetched on the host
   with the registry's HTTP API, pinned by digest in a lock file beside the project's, and run as a `RUN` line of
   the image build, as root, as `snippet` is. A feature that asks for privileges or mounts is refused by name.
6. **Docs:** `doc/project-file.md` says the key, what is taken and what is ignored.
7. **Acceptance:** a scenario in `acceptance/suite/.../project/project-file-check.feature`: a repository whose
   devcontainer file names `ubuntu:24.04` and `forwardPorts: [3000]` gets a task on that image, and the start names
   `forwardPorts` as ignored.

## Acceptance

- Seen to fail first, then green: a repository with a devcontainer file naming an image and a port gets a task on that
  image; the port is named as ignored.
- `doc/project-file.md` says it.

## To be checked

- Which of the file's features are taken in the first step: none in this plan, only `image` and `containerEnv`; and
  whether `build.dockerfile` is taken as a second way to the image (the operator's).
- Whether features are fetched at all, since each runs a script from a registry as root at build time (the
  operator's).
