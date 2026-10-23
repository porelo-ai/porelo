# B266 — A Task Fits The Machine It Runs On

**Status:** Idea.

**What must be true.** A task container and an image build are bounded on any host, large or small, so the work
inside never sizes itself past a limit it cannot see. On a host of 32 or more cores a build inside a task does not
spawn past its pids limit or its memory, and on a small host an image build does not run it out of memory. This is
porelo's part of porelo-project [PJ68](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj68).

## Why

On 2026-10-23 the room's homeserver failed on a rented host of 32 cores. It sized a pool from the cores against
podman's default of 2048 pids. Read in porelo for the same pattern:
- **A task** gets `--pids-limit` 2048 and `--memory` 8g by default (`Limits.DEFAULT_PIDS`, `DEFAULT_MEMORY`), but no
  `--cpus` unless the project sets `limits.cpus` (`ContainerSpec.toArguments`). So `nproc` and a JVM's
  `availableProcessors()` inside read every core of the host. A `make -j$(nproc)`, Maven's `-T 1C` or Gradle's workers
  grow with the host against fixed pids and memory.
- **The image build** (`Podman.buildArguments`) sets no pids, memory or CPU limit at all. Its only bound is the
  command runner's 10 minutes (`ProcessCommandRunner`), the "Timed out after 600 s" porelo-host's Molecule met the
  same day.
- **Open files** are podman's default in both, 1024 soft, which is low for a language server or a large build.

Nothing of porelo's own sizes a pool from the cores: its one fixed pool, the daemon's screen reads, is capped by a
constant.

## To be decided in the analysis

- A default `--cpus` for a task, so `nproc` inside reads the limit, or pids and memory that grow with the host.
- Limits for the image build's containers, and whether they follow the project's `limits`.
- A default for open files, and whether a project may raise it.
- How each is tested without a 32-core machine: by the arguments porelo writes, and on the VM with a forced small
  limit, as the homeserver's red run was made.
