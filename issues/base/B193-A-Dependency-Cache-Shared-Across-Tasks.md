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
   `porelo.yml`), with the project's egress, writing into `data/cache/<project>/<repository>/maven` and nothing else.
   **A fill runs a goal Porelo fixes, `dependency:go-offline`**, which resolves the build's plugins and dependencies
   without running them, never a goal or a profile the repository names. **The exposure left** is what Maven loads
   before any goal: the build extensions the repository declares (`.mvn/extensions.xml`, a plugin with
   `<extensions>true</extensions>`), run at the reviewed commit on the host side with a writable cache; the same
   reviewed code runs in every task anyway. `doc/decisions.md` names it.
2. **Read-only and shared in every task:** mounted at `/porelo/cache/maven` with `:ro,z`. `ContainerSpec` mounts every
   volume with `:Z` today, a private label per container, which on an enforcing host cuts a shared directory off from
   the other running tasks; a cache mount takes the shared `z`. Measured on Fedora 44, SELinux enforcing, podman
   5.8.1: two running containers both read a `:ro,z` mount as uid 1001, neither can write it, as root neither, and a
   third container mounting the same directory with `:Z` leaves the first unable to read it (`Permission denied`).
3. **Used without the task writing it:** Maven 3.9 reads a read-only second repository behind the local one
   (`maven.repo.local.tail`), so a task resolves from the cache and downloads what is missing into its own local
   repository. Porelo sets it through `MAVEN_ARGS` in the task's environment. Measured with Maven 3.9.15, which every
   Porelo repository with a Maven wrapper pins: an offline build resolves wholly from a read-only tail and writes
   nothing to its own repository; online, an artifact missing from the tail is downloaded into its own repository; the
   tail's files, sizes and times are unchanged afterwards. `porelo-intellij` builds with Gradle and the interface with
   Flutter, which the Maven cache does not reach.
4. **When a fill runs:** on a person's `porelo project cache fill`, and at a start when the reviewed default branch
   moved since the last fill. The cache records the commit it was filled from; a start compares it with the mirror's
   default branch after the gate's fetch, and when they differ it fills first, says so, and waits for the fill. A fill
   that fails keeps the cache as it was, and the start says so with the fill's log and goes on with the older cache.
5. **Commands:** `porelo project cache fill <project> [-r <repository>]`, `porelo project cache status`, and the cache
   removed by `clear` and project deletion with the project.
6. **Docs and tests, red first:** a fill writes only the cache; a task's own download never reaches it (a file placed
   in the task's `~/.m2` is absent from the cache and from a second task); the mount is read-only from inside; a task
   resolves an artifact from the cache with no egress to the repository's host.

## Acceptance

- A second task of a project resolves its build from the cache with the repository host's egress withdrawn, where a
  task without the mount cannot.
- A file a task writes into its own `~/.m2` is absent from the cache and from a second task; a write into
  `/porelo/cache/maven` from inside a task is refused, as root too.
- A start after the default branch moved fills first and says so; a start with no move fills nothing.
- A fill runs no goal of the repository: a plugin the repository binds to a phase, writing a marker, leaves none
  after a fill. Seen to fail: the same repository built with `verify`.
