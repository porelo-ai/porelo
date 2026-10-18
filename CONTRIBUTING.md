# Contributing

Changes to Porelo follow the project's guide: how an issue is written, how a change is proposed and reviewed, and what
a commit carries. It is one page for every Porelo repository:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Building and testing:** [doc/build.md](doc/build.md). It needs JDK 25, the pinned GraalVM. `./mvnw -B verify`
  builds the whole reactor with every module's unit tests, and `./mvnw -B -Pdocuments test` checks the documents.
- **The acceptance suite** runs from here against a machine with rootless podman, reached over ssh: a local VM, or a
  machine the Build rents for each leg. See [acceptance/README.md](acceptance/README.md).
- **Requirements** are in [issues/README.md](issues/README.md), grouped by state.
