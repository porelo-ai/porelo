# B240 — A Repository Named With Dots

**Status:** Implementing.
**Size:** S - one pattern in `core`, a parallel check in `app-model`, their tests and a line of the project file's page.

**What must be true.** A project names a repository by its real name, dots included (`porelo-ai.github.io`), as a
forge allows it, so a task's repository is the repository a person knows.

## Why

Measured on `0.5.1~snapshot.307`: `repositories: porelo-ai.github.io:` in a project file is refused with "Invalid
repository name 'porelo-ai.github.io', expected lower-case letters, digits and hyphens". The name becomes a mirror
directory, part of an image name and part of a container's; each allows a dot, but `..` and a leading dot must stay
refused.

## Plan

Read in `Repository`, `DefaultProject`, `RepositoryAddress`, `GateSupport`, `ImageChoice`, `Project`, `TaskRunner`,
`Prune` and `ContainerName`.

1. The name becomes a mirror directory, an override's directory, part of an image name (`porelo/<project>_<repository>`,
   split at the first `_`), a build context and its lock, and a label; never part of a container's name. A dot is
   valid in each, between two letters or digits.
2. **`Repository.NAME_PATTERN`** takes labels joined by single dots, each dot between two letters or digits; `..`, a
   leading or trailing dot, `.-` and `-.` stay refused. The refusal names dots; `DefaultProject` checks the same
   way, and `RepositoryAddress.nameOf` keeps a checkout's dots.
3. **Docs:** the name's rule under `repositories:` in `doc/project-file.md`.
4. **Red first:** `RepositoriesTest.aRepositoryNamedWithDotsIsAccepted`, then
   `aRepositoryNameWithTwoDotsOrALeadingDotIsRefused`.

Measured: podman 5 builds and lists `porelo/demo_porelo-ai.github.io` (a rootless build and `podman image ls`).

**Decided:**
- A name ending in a hyphen is refused from now on, since its image cannot be built anyway; the refusal names the rule.

## Acceptance

- A project naming `porelo-ai.github.io` reads, and its task's image is `porelo/<project>_porelo-ai.github.io` and its
  mirror `porelo-ai.github.io.git`. Seen to fail first: the refusal today.
- `..x`, `.x`, `x.` and `a.-b` are still refused.
