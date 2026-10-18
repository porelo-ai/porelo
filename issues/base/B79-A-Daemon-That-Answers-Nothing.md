# B79 — A Daemon That Answers Nothing

**Status:** Review. Seven runs on rented two-CPU machines, 2026-10-18, no hang.
**Size:** S - the mitigation is built and measured to hold; a dump is read the day one hangs.

**What must be true.** A call on the daemon's socket, or on an agent's, is answered or refused within a bound,
whatever the process's own background work is doing - and when one is not, the reason is known rather than
guessed.

What is known: a daemon of an acceptance scenario's own, started after another was killed with SIGKILL and with
about fifteen projects followed, answered `GetInfo` and then no call for four minutes, logging nothing and using
six seconds of CPU; a scenario asking a daemon for `Agents` and `Start` hung the same way twice in one day on two
VMs, and passed on reruns. Agents built against the old API hung the same way, three times in 1200 lookups,
one printing "still running; since it was ready it wrote nothing" - stuck, not slow and not dead. The likeliest
cause - background passes and connection reads on virtual threads sharing two carriers - is taken away, but the
old stub never hung either in 1600 lookups, so that it was the cause is inferred, not measured.

## Plan

Built, read in the code on 2026-10-11:

- Every `VarlinkServer` connection runs on a platform thread unless told otherwise
  (`wire/.../varlink/VarlinkServer.java`, `ServingThreads`, switched by one variable for a measurement).
- Every long-lived pass runs on a platform thread of its own (`apps/app-base/.../BackgroundPass.java`, held by
  `BackgroundPassTest`); an agent's handshake waits on a platform thread (`agents/api/.../InstalledAgent.java`).
- `porelod` is built with `--enable-monitoring=threaddump` (`daemon/pom.xml`), so `SIGQUIT` writes a dump to the
  journal without stopping it.
- Still on virtual threads: a task's log watcher in `PoreloDaemon` (one per running task) and the follow
  credential's server in `FollowCredential`. Neither serves the socket.

What is left, in order:

1. **A run to try it**: on the two-CPU VM, a daemon killed with SIGKILL and started again with fifteen or more
   projects followed, then the acceptance suite without `@restart`. A small script in the scenario's account
   starts every call with a bound (`GetInfo`, `Agents`, `Start`) and on a call past it sends `SIGQUIT` and keeps
   the journal. Repeated five times with platform threads.
2. **The same with virtual threads** (`PORELO_THREADS=virtual`, which `ServingThreads` reads), which [B125](README.md#b125) needs
   anyway: a hang there and none with platform threads is the measurement this issue lacks.
3. **On a hang**, the dump is read and the waiting frame named here; the fix gets a test that holds the thread
   it waits on, watched to fail.

Nothing goes red first in code: the red is a hang caught with its dump.

**Run, 2026-10-18: seven times, no call past its bound.** Each on a rented `ccx13` (2 dedicated vCPUs, 8 GB) from
the ubuntu CI image, prepared by porelo's own leg with 2 accounts. In the leg's account: the daemon's unit installed
and `porelo setup`, 15 projects followed, then `porelod` killed with SIGKILL and started again at once (the run
stopped unless the kill and the restart were seen, with the threads asked), a script asking `GetInfo`, `Agents`,
`List` and a dry-run `Start` every 15 s with a 10 s bound and `SIGQUIT` past it, and the acceptance suite without
`@restart`, two accounts in parallel.

| Run | Threads | Suite | Bounded calls | Past the bound | Slowest | Median |
|---|---|---|---|---|---|---|
| c1 | platform | 233, 0 failed | 286 | 0 | 2.81 s | 0.24 s |
| p1 | platform | 233, 0 failed | 312 | 0 | 1.37 s | 0.25 s |
| p2 | platform | 233, 0 failed | 312 | 0 | 2.13 s | 0.23 s |
| p3 | platform | 233, 1 failed | 298 | 0 | 2.21 s | 0.22 s |
| v1 | virtual | 233, 0 failed | 292 | 0 | 2.18 s | 0.22 s |
| v2 | virtual | 233, 0 failed | 302 | 0 | 3.05 s | 0.24 s |
| v3 | virtual | 233, 0 failed | 328 | 0 | 2.31 s | 0.26 s |

2130 bounded calls, none past 10 s, so no dump was taken; a few more were refused at once while a scenario had the
account's daemon stopped. At the rate seen before the mitigation - three of 1200 lookups stuck - about five would
have been expected, which is what "enough runs" was taken to mean. The first follow pass after the restart, with 15
projects followed, kept nothing waiting: the slowest call of each run's first two rounds took 0.13 s. The one failed
scenario on `p3` was no hang: "an agent whose provider refused it says so ... and is not called idle" took 160 s
there against 32 to 36 s on the six others, and the task still said `activity idle` when its 90 s loop ended; the
daemon answered the watcher throughout. Seen once in seven; an issue of its own if it comes again.

## Acceptance

- The next time a daemon or an agent answers nothing, its thread dump (`SIGQUIT`) is read and names where the
  call waits. Seen to fail: a hang with no dump taken, or a dump that shows every connection thread idle.
- A full acceptance run on a two-CPU VM, started right after a daemon is killed and with fifteen or more
  projects followed, answers every call within its bound; repeated until either a hang is seen or enough runs
  pass to say the mitigation holds.

## To be checked

- On the fedora VM the daemon once started a second `porelo-agent-stub serve` on the socket the first one held;
  whether that is the same fault or another (Agent Core's). Not seen in the seven runs.
