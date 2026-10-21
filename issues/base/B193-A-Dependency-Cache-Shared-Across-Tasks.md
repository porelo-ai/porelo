# B193 — A Dependency Cache Shared Across Tasks

**Status:** Analyzed.
**Size:** XL - one ecosystem at a time; splits into Maven first (L: the cache, its mount, its fill from reviewed work,
the start using it, docs and acceptance) and npm, pub and pip after it (an issue each, M, on the same mount and fill).

**What must be true.** The dependencies a task downloads - Maven, npm, pub, pip - are cached once for a project's
tasks and handed into each task read-only, so the next task does not download them again; what a task installs or
builds itself stays its own and never reaches another task through the cache.

From [PJ39](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj39), which replaces the split `~/.m2` of today's host sessions with it.

## Plan

Read on 2026-10-21 in `ContainerSpec`, `TaskLaunch`, `GateSupport` and the Maven wrappers the Porelo repositories pin.

1. **Never filled by a task.** A task's own `~/.m2` is what it wrote, and harvesting it would hand one task's
   downloads, or what it placed there, to every other task. The cache is filled only by a fill run on the host side:
   a throwaway container from the repository's image, on its mirror's reviewed default branch (as [B192](README.md#b192) reads
   `porelo.yml`), with the project's egress, running the repository's dependency resolution, writing into
   `data/cache/<project>/<repository>/maven` and nothing else.
2. **Read-only and shared in every task:** mounted at `/porelo/cache/maven` with `:ro,z`. `ContainerSpec` mounts every
   volume with `:Z` today, a private label per container, which on an enforcing host would cut a shared directory off
   from the other running tasks; a cache mount takes the shared `z`.
3. **Used without the task writing it:** Maven 3.9 reads a read-only second repository behind the local one
   (`maven.repo.local.tail`), so a task resolves from the cache and downloads what is missing into its own local
   repository. Porelo sets it through `MAVEN_ARGS` in the task's environment.
4. **When a fill runs - an open question below.**
5. **Commands:** `porelo project cache fill <project> [-r <repository>]`, `porelo project cache status`, and the cache
   removed by `clear` and project deletion with the project.
6. **Docs and tests, red first:** a fill writes only the cache; a task's own download never reaches it (a file placed
   in the task's `~/.m2` is absent from the cache and from a second task); the mount is read-only from inside; a task
   resolves an artifact from the cache with no egress to the repository's host.

## Open questions

- **When the cache is filled:** (a) only on a person's `porelo project cache fill`, or (b) also at a start when the
  reviewed default branch moved since the last fill, which makes a start slower by the fill. Recommended: (a) first,
  with `cache status` saying how old it is.
- **What runs in a fill:** dependency resolution runs build plugins and extensions the repository names, at the
  reviewed commit, with the project's egress, outside any task. The same reviewed code runs in every task anyway, but
  here on the host side with a writable cache. (a) accept it, as [B192](README.md#b192) accepts the image build, or (b) resolve with a
  fixed Porelo-chosen goal set only (`dependency:go-offline` with plugins not executed beyond resolution).
  Recommended: (b) where Maven allows it, and (a) named as the exposure where it does not.

## To be checked

- That `maven.repo.local.tail` is honoured by the Maven version each Porelo repository's wrapper pins, and what a
  repository with an older wrapper does with it.
- That a `:ro,z` mount is readable by the task's user on Fedora with SELinux enforcing, and not writable.
