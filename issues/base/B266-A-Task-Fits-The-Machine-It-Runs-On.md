# B266 — A Task Fits The Machine It Runs On

**Status:** Implementing.
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

1. **A task gets a CPU limit of 4 by default.** `Limits.DEFAULT_CPUS` (`"4"`) joins the memory and pids defaults, so `--cpus` is
   always written. `ProjectReader` also uses it when a limits block names only another key, and `ProjectSchema`
   advertises the same default to editors.
   `limits.cpus: none` still takes it off, and a project's value replaces it.
2. **The limit is made visible**: `ContainerSpec` sets `OMP_NUM_THREADS` to the CPU limit rounded down, at least 1, in
   every task with a CPU limit. The plain variable uses `--env-merge OMP_NUM_THREADS=${OMP_NUM_THREADS:-N}`
   so a nonempty image value survives; an explicit project value uses the existing environment map and wins.
   `nproc` and what sizes itself by it read the limit; a JVM reads the CPU quota itself (to be checked).
   An image or a project that sets `OMP_NUM_THREADS` keeps its value.
3. **The image build is bounded by the project's limits**: `Podman.buildArguments` writes `--memory` and
   `--cpu-period`/`--cpu-quota` from them. The build runs under `taskset` with as many cores as the limit, since
   builds of one image take a lock; builds of different images may share pinned cores.
   The wrapper is part of the full command returned by `buildArguments`, for the terminal and runner alike.
   Its core count is the CPU limit rounded up, capped at the host's `Runtime.availableProcessors()`,
   and the quota comes from the same parsed CPU value. With `cpus: none`, neither the wrapper nor these build limits
   are written. `RUN make -j$(nproc)` reads the limit.
4. **Open files stay podman's**, as measured. A Fedora reading under *To be checked* may change that.
5. **`doc/project-file.md`** names the default under `limits`; **`doc/decisions.md`** gains the accepted risk above,
   with its index row; **`doc/reach.md`** gains the row that a task's CPU is
   bounded and what inside it can still read the host's core count (`getconf`). `ReachDocumentTest` holds the number.
6. **Tests, red first:**
   - `LimitsTest` and `ProjectReaderTest`: default CPU limits survive an absent or partial limits block;
     `ProjectKeysTest` checks that the schema offers the same default;
   - `ContainerSpecTest`: the default limits write `--cpus` and `OMP_NUM_THREADS`; `cpus: none` writes neither;
     fractional limits round the hint down to at least one, and explicit image/project settings win;
   - `PodmanTest`: the build's arguments carry `--memory` and the CPU quota of the project's limits, under `taskset`;
   - an acceptance step: a task with `limits.cpus: 1` reads `nproc` 1, and its image's `RUN nproc` 1.
7. A changelog line under *Changed*: every task now has a CPU limit of 4 by default.

- **Build estimate=180**, like porelo [B258](README.md#b258) (M), with fewer documents and one acceptance step.

## Acceptance

- On any host a task's `nproc` reads its CPU limit, and the default limit is written without a project naming it.
- An image build runs under the project's memory and CPU limits, and its `nproc` reads the limit.
- A project with `limits.cpus: none` has no CPU limit, as today.

## Decided

- **The default CPU limit is 4 per task**, fixed rather than a share of the host: with the fixed 8 GB and 2048
  processes, a pool sized from 4 cores stays inside them on any host. `limits.cpus` in the project replaces it, and
  `none` removes it.
- **`getconf` and `sysconf` reading the host's cores is an accepted risk**: no task is pinned with `taskset` on every
  `start` and `exec`. It goes into `doc/decisions.md` in the commit that builds this, since `doc/` says what is true
  now. The entry reads: *the exposure*, a tool that sizes itself by those two runs more threads than the limit and is
  throttled by the quota, never stopped, and its pids and memory are still bounded; *why it stays*, pinning shares
  cores badly between tasks, since each would be bound to named cores, and every `exec` would need it; *what would
  change the answer*, a host that delegates the `cpuset` controller to the account, or a pids or memory limit that
  such a tool reaches before the quota slows it.

## To be checked

- That a JVM in a task with `--cpus 2` reads 2 from `availableProcessors()` (a JDK image on the VM).
- That GraalVM's `native-image` in a task sizes its build threads from the CPU limit (the quota or
  `OMP_NUM_THREADS`), not the host's cores; a native build in a task is the largest such build today.
- Open files and the delegated controllers on the Fedora VM.
- That `taskset` (util-linux) is on every machine the packages install on, or is declared as a dependency.
