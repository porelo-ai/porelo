# B125 — What A Daemon Costs, And On Which Threads

**Status:** Implementing.
**Size:** M - the idle measurement, the bound on loops and the listing of never-started containers are done; what is left: the idle measurement again and the run on virtual threads.

**What must be true.** What one `porelod` costs is measured, idle and at work, and its loops run on
the kind of thread that the measurement and [B79](README.md#b79)'s fault support. The decision is written down with the
numbers that made it, not inferred.

## Why

In Walk 11 (2026-10-07) two daemons on one VM used 232 % and 256 % CPU, and a start in the
interface lost its `Credentials` call after 30 seconds. The cause was loops that never waited, not
the kind of thread. Each task's direct chat, and a project's conversation, had a loop that waits on
its transport. When a task had no account in the vault, or the vault was shut, that wait came back
at once with nothing and no error, and the loop asked again with no pause. Two or three such loops
turned a core each, for tasks whose containers had exited two days earlier. The pause, the fix for
that load, is in `caf9585d`. A loop that never waits burns its core on a virtual thread just as on
a platform thread.

The kind of thread is a separate question: how many threads a machine carries, and whether a call on
the socket always finds a thread to run on. The daemon's long-lived passes (`BackgroundPass`) and
every varlink connection run on platform threads since [B79](README.md#b79). A daemon once answered no call for four
minutes. The likeliest cause was background passes and connection reads on virtual threads, pinned in
native calls, holding both carriers of a two-CPU VM. That cause was inferred, not measured, and the
hang has not been seen since. Whether virtual threads would serve better was the open question.

## Plan

Built, read in the code at `30c8169b`: the pause (`caf9585d`, `MessageWaitPaceTest`); platform threads with
`PORELO_THREADS=virtual` kept for measuring (`wire/.../varlink/ServingThreads.java`); screens read from a file the task
writes (`apps/app-base/.../ScreenFile.java`) and eight at once (`AgentWaiting.readScreens`); a heap bound for each
helper; the leaks of the first round. Both measured rounds below say platform threads stay.

Built after the idle measurement: **a loop only for a task that can be waited on.** `MessageWatch.waiting` starts a
task's `porelo-direct-<container>` loop, and keeps it, only while its container runs and the vault holds an account
for it; the loop also ends the moment that account cannot be read (the vault shut). A project's
`porelo-conversation-<project>` loop runs only while one of its tasks qualifies so, and is still handed to every task
of the project, a stopped one too. An exited task's messages wait for the timed pass, which delivers to stopped tasks
as before. The 1.4 % of a project with no task is, read in the code and not yet measured, the same mechanism: the last
mailbox removed, `passOnce` returned before it looked at the loops, so the conversation's loop kept the members of the
last pass and woke every five seconds; that pass now ends them. `MessageWatchLoopsTest` holds all four cases, each seen to fail with its check
undone.

And **a container created and never started stays a task.** `Podman.poreloTasks` lists a `Created` container whose id
is recorded and drops one whose id is not, as it drops any container merely named like a task (`PodmanTest`). The id
was the gap: `TaskRunner` recorded it only in the runtime directory, and the rest of the task's state is saved where a
reboot does not reach only once it started. A start the hook refused, then a reboot or the end of the last session,
left a container that was no task, so `porelo task remove` answered that it is none and the name stayed taken.
`TaskRunner.recordCreated` now writes the id to the saved record as well; `RecognizedTasksTest` removes such a
container after its runtime directory is gone, seen to fail with the id in the runtime directory alone.

What is left, in this order:

3. **The idle measurement**, on the two-CPU VM, of the native build, before and after `caf9585d`: no project, exited
   tasks with no account, one running task, the vault shut and open - CPU over a minute and threads, a row each in
   this issue.
4. **The [B79](README.md#b79) run on virtual threads**: the same VM, `porelod` killed with `SIGKILL` and started at once with
   `PORELO_THREADS=virtual`, fifteen or more projects followed, a full acceptance run without the restart scenarios;
   a hang gets a `SIGQUIT` dump. The outcome and its reason go under the measurements.

## Acceptance

- **Measured, numbers in this issue:** CPU over a minute, and the number of threads, of one `porelod`
  that is idle with no project, with exited tasks, with one running task, and with the vault shut and
  open. Each is measured before and after `caf9585d`, on the two-CPU VM. Seen to fail: a daemon with
  exited tasks and no account for them above 5 % of one core.
- **Every daemon loop that waits on something outside it rests when that thing did not wait**, each
  with a test watched to fail. Today these are the two transport loops (`MessageWaitPaceTest`). The
  others sleep or block (`porelo-messages`, `porelo-message-notices`, `porelo-follow`, `porelo-upstream`).
- **Virtual or platform, decided with a measurement:** the [B79](README.md#b79) scenario (two-CPU VM, a daemon started
  right after a SIGKILL, fifteen or more projects followed, a full acceptance run) run with the
  passes and connections on virtual threads. If calls hang, a thread dump (`SIGQUIT`) names where they
  wait, and the platform threads stay, with the reason recorded here. If none hangs in enough runs to
  say so, the threads that only wait move to virtual threads, and those that block in native calls stay
  on platform threads of their own.
- **The thread count at rest is bounded,** whichever kind: a loop per exited task is not one per
  task ever started. A task with no account in the vault is not waited on at all.

## To be checked

- How many threads a daemon holds with N tasks that each have a conversation: measured at 20-24 threads for up to
  30 tasks with none, so the direct-chat loops are what is left to count (Agent Core's).
- Whether a native call (`podman`, `git`, `ssh`, a transport adapter) pins a carrier in today's
  GraalVM native image, measured rather than taken from the JDK's documentation (Agent Core's).
- Whether a second account's `0.4.0~snapshot.309` daemon on the test machine, with its vault shut, shows the same
  turning threads after the fix, or that account is simply moved to a current build (Agent Core's).

## Measured, 2026-10-07

The native build `5956eb3f` in one account on the Ubuntu VM (6 vCPUs, 11 GB), every other account's
`porelod` stopped, no heavy run on the host (its load1: median 2.1, max 4.8). Each step started N stub tasks at once,
each working (a line on the screen, a commit every 3 s, a push every tenth step), and sampled `porelod` every 5 s for
10 minutes, with one `List`, `Credentials` and `Following` call over `varlinkctl` (about 100 ms of which is
`varlinkctl`'s own). Platform threads first, then `porelod` restarted with `PORELO_THREADS=virtual`.

| Threads | Tasks | CPU median / max | Threads | RSS | `List` median / max | `Credentials` p95 / max | Start median / max | Failed calls |
|---|---|---|---|---|---|---|---|---|
| platform | 10 | 0 % / 29 % | 20-21 | 52 MB | 1.2 s / 2.6 s | 108 / 124 ms | 20 s / 23 s | 0 |
| platform | 20 | 27 % / 29 % | 22-24 | 52 MB | 2.5 s / 5.0 s | 133 / 433 ms | 40 s / 47 s | 0 |
| platform | 30 | 27 % / 30 % | 23 | 45 MB | 3.8 s / 6.2 s | 157 / 175 ms | 62 s / 76 s | 0 |
| virtual | 10 | 0 % / 30 % | 18-19 | 48 MB | 1.2 s / 2.6 s | 108 / 128 ms | 21 s / 24 s | 0 |
| virtual | 20 | 27 % / 30 % | 20-21 | 48 MB | 2.5 s / 5.1 s | 111 / 129 ms | 36 s / 45 s | 0 |
| virtual | 30 | 29 % / 31 % | 21 | 46 MB | 3.8 s / 6.7 s | 139 / 524 ms | 60 s / 73 s | 0 |

Every agent worked in every step (1950, 3800 and 5500 commits in all). RSS did not grow while the tasks worked (at
most +0.18 MB/min).

**What follows:**

- **Platform or virtual makes no difference** that this measurement can see, so platform threads stay, with
  [B79](README.md#b79)'s reason. `PORELO_THREADS=virtual` stays, so it can be measured again. `porelod` holds a few dozen threads at
  30 tasks; the work is processes (`podman`, `git`), which a virtual thread does not make faster. Where virtual
  threads fit - many long waits on the network in plain Java - Porelo already uses them: the credential proxy serves
  each connection on one.
- **`List` grows with the tasks**: it reads each running task's screen one after the other (`podman exec tmux
  capture-pane`, about 100 ms each), so 30 tasks take about 4 s. To fix: the screens read in the background, `List`
  answered from what was read.
- **Memory is the limit, and it is the helpers', not `porelod`'s**: per task the host runs `porelo vault serve`
  (179 MB), `shield watch` (54 MB), `gate serve` (47 MB), `shield read` (41 MB) and `dnsmasq` (6 MB), about 330 MB,
  against the stub container's 5 MB and `porelod`'s 50 MB. 30 tasks need about 10 GB. To fix: a heap bound for each
  helper, measured before and after.
- **Small leaks after each removal**: with platform threads `porelod` went from 16 threads and 10 descriptors to 20
  threads and 20 descriptors over three steps; build readers outlived their tasks (two after the first step). To fix,
  each with a test.
- A container that was created but never started is not listed as a task, so `porelo task remove` cannot remove it
  (built since: its id is recorded where a reboot does not reach).

## Second round, measured 2026-10-07

The native build `7c078c0c` in the same account, platform threads, the host's load1 median 2.2 (max 3.9). Each stub task
worked as in the first round, and at every step also sent a streamed request through its credential proxy to a fake
provider on the VM (TLS with a certificate trusted through `~/.config/porelo/ca-certificates.pem`, about 4 s per
answer, one in twenty refused with 429) and wrote 8 MiB to its disk.

| Tasks | CPU median / max | RSS | `List` median / max | `Credentials` p95 | Start median | Proxy answers / 429 | Failed calls |
|---|---|---|---|---|---|---|---|
| 5 | 3 % / 29 % | 68 MB | 0.5 s / 0.7 s | 108 ms | 13 s | 208 / 13 | 0 |
| 15 | 24 % / 27 % | 54 MB | 1.2 s / 1.9 s | 117 ms | 32 s | 774 / 47 | 0 |

- **The helpers per task**, at 15 tasks: `vault serve` 45 MB (179 before), `shield watch` 39 (54), `gate serve` 42
  (47), `shield read` 38 (41) - about 165 MB instead of 330. The VM held 3.6 GB at 15 tasks, 6.3 GB at 20 before.
- **No leak**: three cycles of 5 tasks started, working for a minute and removed left `porelod` at 54 MB, 12-20
  descriptors and 31-32 threads each time. The threads above the 10 of a fresh daemon are idle pool workers, which
  end on their own; build readers no longer outlive their tasks.
- **A start loads every installed agent's adapter** (four here) while it runs, about 15 MB each; they end with the
  start.
- **`List` is faster, not fast**: the screens are now read eight at once, but each read is a `podman exec`, 400-600
  ms under this load. The interface's `Watch` recomputes the listing every 500 ms, so an open interface costs one
  `exec` per running task every 3 s. To fix: the screen is written to a file inside the task when it changes, and read
  from the host without `exec`.

## Idle, measured 2026-10-17

The native builds `6936d53f` (after `caf9585d`) and `7dda0bc9` (`caf9585d`'s parent, built under the product's former name) in one account
on the two-CPU `fedora` VM (Fedora 44, 2 vCPUs), each `porelod` alone in the account. CPU is the daemon's own user and
system time over 60 s from `/proc/<pid>/stat`, so it does not depend on what else the host runs; threads from
`/proc/<pid>/status` at the end. Stub tasks in a guarded project, stopped with `task stop`. "Mail" is a project whose
file names a transport, `mail.transports.idle`: an adapter of the measurement's own, in the account's transports
directory, that hands out no secrets, so every task has no account and its `poll` comes back at once with exit 78.

| Build | State | CPU of one core | Threads |
|---|---|---|---|
| after | no project followed, vault open | 0.02 % | 9 |
| after | one running task | 0.05 % | 10 |
| after | three exited tasks, no transport | 0.27 % | 10 |
| after | the same, vault shut | 0.27 % | 13 |
| after | three exited tasks in "mail", no account | **3.10 %** | 14 |
| after | the same, vault shut | 2.83 % | 14 |
| after | "mail" followed, its tasks removed | 1.43 % | 11 |
| before | no project followed, vault open | 0.02 % | 7 |
| before | three exited tasks in "mail", no account | **183.57 %** | 12 |

- **`caf9585d` holds:** three exited tasks with no account kept nearly both CPUs busy before it, and keep 3 % of one
  core after it. Below this issue's 5 %, but not nothing: each such task still has a loop of its own that wakes every
  five seconds (the three threads above the idle daemon's), which step 1 removes.
- **A project that names a transport and has no task still costs 1.4 %**, the project's own poll with nothing to
  poll for. Read in the code, the poll kept the members of the last pass once the last mailbox was removed; ended
  now with the loops of exited tasks. Measured again in step 3.
