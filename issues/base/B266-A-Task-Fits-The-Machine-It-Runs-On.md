# B266 — A Task Fits The Machine It Runs On

**Status:** Analyzed. In porelo-project [PJ68](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj68)'s
block, built before the first host test with real builds in tasks.
**Size:** M - the task's and the build's podman arguments (`ContainerSpec`, `Podman`), the project file's defaults,
`doc/project-file.md` and `doc/reach.md`, an acceptance step.

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
- **Open files** were thought to be podman's default of 1024. Measured below: they are not, on Ubuntu 26.04.

Nothing of porelo's own sizes a pool from the cores: its one fixed pool, the daemon's screen reads, is capped by a
constant.

## Measured

On the Ubuntu VM (`ubuntu26.04`, 6 cores, podman 5.7.0, account `core`), with `ubuntu:24.04`, on 2026-10-24:

| Run | `nproc` inside |
|---|---|
| `podman run --cpus 2` | 6 |
| `podman run --cpuset-cpus 0-1` | refused: the `cpuset` controller is not delegated to the account (only `cpu memory pids`) |
| `taskset -c 0-1 podman run` | 2 |
| `taskset -c 0-1 podman start`, then a plain `podman exec` | 6: an `exec` takes its caller's affinity, not the container's |
| `taskset -c 0-1 podman build`, a `RUN nproc` | 2 |
| `podman build --cpu-period 100000 --cpu-quota 200000`, a `RUN nproc` | 6 |
| `podman run --cpus 2 -e OMP_NUM_THREADS=2` | 2; `getconf _NPROCESSORS_ONLN` still 6 |

- **`--cpus` is a real bound but invisible to `nproc`**: the CPU quota limits the time, not what a process sees.
  `--cpuset-cpus`, which `nproc` would read, needs a controller a rootless account does not get by default.
- **CPU affinity (`taskset`) is visible**, but it pins the work to named cores. Several tasks pinned alike would share
  the same cores and leave the rest idle. Each `podman exec` would also need it, since it takes its caller's affinity.
- **`OMP_NUM_THREADS` is read by coreutils' `nproc`**, so `make -j$(nproc)` follows it; `getconf` and `sysconf` do not.
- **`podman build` has no `--pids-limit`**, but takes `--memory`, `--cpu-period`/`--cpu-quota` and `--ulimit`.
- **Open files inside a container are 524288**, soft and hard, so there is nothing to raise on Ubuntu 26.04.

## Plan

1. **A task gets a CPU limit by default.** `Limits.DEFAULT_CPUS` joins the memory and pids defaults, so `--cpus` is
   always written. `limits.cpus: none` still takes it off, and a project's value replaces it.
2. **The limit is made visible**: `ContainerSpec` sets `OMP_NUM_THREADS` to the CPU limit rounded down, at least 1, in
   every task. `nproc` and what sizes itself by it read the limit; a JVM reads the CPU quota itself (to be checked).
   An image or a project that sets `OMP_NUM_THREADS` keeps its value.
3. **The image build is bounded by the project's limits**: `Podman.buildArguments` writes `--memory` and
   `--cpu-period`/`--cpu-quota` from them. The build runs under `taskset` with as many cores as the limit, since
   builds take a lock and run one at a time, so no two of them share pinned cores. `RUN make -j$(nproc)` reads the
   limit.
4. **Open files stay podman's**, as measured. A Fedora reading under *To be checked* may change that.
5. **`doc/project-file.md`** names the default under `limits`; **`doc/reach.md`** gains the row that a task's CPU is
   bounded and what inside it can still read the host's core count (`getconf`). `ReachDocumentTest` holds the number.
6. **Tests, red first:**
   - `ContainerSpecTest`: the default limits write `--cpus` and `OMP_NUM_THREADS`; `cpus: none` writes neither;
   - `PodmanTest`: the build's arguments carry `--memory` and the CPU quota of the project's limits, under `taskset`;
   - an acceptance step: a task with `limits.cpus: 1` reads `nproc` 1, and its image's `RUN nproc` 1.
7. A changelog line under *Changed*: every task now has a CPU limit.

- **Build estimate=180**, like porelo [B258](README.md#b258) (M), with fewer documents and one acceptance step.

## Acceptance

- On any host a task's `nproc` reads its CPU limit, and the default limit is written without a project naming it.
- An image build runs under the project's memory and CPU limits, and its `nproc` reads the limit.
- A project with `limits.cpus: none` has no CPU limit, as today.

## Open questions

- **The default CPU limit for a task.** Proposed: 4. That is enough for an agent and a build. Six tasks on a 32-core
  host share it fairly, and with the 8 GB and 2048 processes already fixed, a pool sized from 4 cores stays inside
  them. The alternative is half the host's cores, at least 2: it grows with the host, so on a large one the pids and
  memory defaults can be outgrown again.
- **`getconf _NPROCESSORS_ONLN` and `sysconf` still read the host's cores.** Only `nproc` and the JVM follow the limit,
  so a tool asking those two sizes itself past the limit and is slowed by the quota, never stopped. Accept that, or pin
  each task to its own cores with `taskset` on every `start` and `exec`? Pinning shares cores badly between tasks and
  is a change on many call sites.

## To be checked

- That a JVM in a task with `--cpus 2` reads 2 from `availableProcessors()` (a JDK image on the VM).
- Open files and the delegated controllers on the Fedora VM.
- That `taskset` (util-linux) is on every machine the packages install on, or is declared as a dependency.
