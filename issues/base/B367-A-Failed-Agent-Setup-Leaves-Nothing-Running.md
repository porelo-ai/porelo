# B367 — A Failed Agent Setup Leaves Nothing Running

**Status:** Idea.
**Size:** S, proposed - the order of a start's steps after the container is up, one fallback in reading the kept
number and one in the daemon's check, each with its test.

**What must be true.** An attended task whose agent setup fails ends as a task that never started: its container and
helpers stopped and nothing of it left that a later `task remove` cannot clean up. A second reading of
[B361](README.md#b361) on 2026-10-31 found five places where the number of subagents a task allows, or the start that
refuses it, does not yet hold:

- A failed setup on a start without `--rm` stops nothing: the stop after a failed start is skipped for a task that is
  kept, so the container, broker, relay and gate keep running while the start answers 70.
- The failed setup returns before the task's records and vault entries are written, so a forge token the host
  already gave the task is never retired by `task remove`, and a later start of that container reads its number as
  0 and brings it up without helpers.
- After a reboot, a continuation reads the kept number before the task's records are put back, so it reads 0: the
  task's own number is refused and 0 is taken, while the agent still gets the kept one.
- The daemon's `Start` asks the host's keys and `podman ps` before it refuses a `subagents` that is no whole number.
- `CanStart` disagrees with `Start` for a multi-repository project named as its own repository, and for a named
  repository with `noGate`, whose agent and limits `CanStart` does not take.

Each is answered by a test that fails today; the meaning of the number does not change.
