# B79 — A Daemon That Answers Nothing

**Status:** Implementing.
**Size:** S - the mitigation is built; what is left is runs on the two-CPU VM and reading a dump if one hangs.

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

## Acceptance

- The next time a daemon or an agent answers nothing, its thread dump (`SIGQUIT`) is read and names where the
  call waits. Seen to fail: a hang with no dump taken, or a dump that shows every connection thread idle.
- A full acceptance run on a two-CPU VM, started right after a daemon is killed and with fifteen or more
  projects followed, answers every call within its bound; repeated until either a hang is seen or enough runs
  pass to say the mitigation holds.

## To be checked

- Whether the first follow pass after a start, with many projects followed, keeps calls waiting (Agent Core's,
  read from the runs above).
- On the fedora VM the daemon once started a second `porelo-agent-stub serve` on the socket the first one held;
  whether that is the same fault or another (Agent Core's).
- How many runs without a hang are enough to say the mitigation holds (Agent Core's).
