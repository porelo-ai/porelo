# B367 — A Failed Agent Setup Leaves Nothing Running

**Status:** Review.
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

## Done

- A start that fails before it hands the task on stops what it started, whether or not the task is kept: a start
  without `--rm` whose agent setup fails, or that fails in any other way once its container is up, stops the container
  and its helpers through `task stop`'s path and keeps it stopped for a look, as a start with `--rm` did. A task that
  was handed on - an attended session, an unattended run - keeps its earlier ending: kept means left as it is.
- The agent's setup runs right after the container starts, and its files are written then, before the relay, the
  gate and the workspace; a file under the workspace waits for the workspace's checkout, which refuses to overwrite
  a file its repository tracks and, stopped by one, left the workspace without the push that reaches the gate. A
  setup that fails leaves only the stopped container and what was made for it before - its state directory, its
  mailbox and its place in the project's conversations - all of which `task remove` ends. Its profile is not written
  on purpose: a later start of that container comes back as a shell, reading its number as 0, and never starts the
  attended agent without the setup that carries its switch.
- A host's forge token is taken last, right after the task's key is kept in the vault, since the kept key is what has
  `task remove` end the token: taken before the gate and the workspace, a start that failed between them left the
  token live until its certificate ran out. This is the smallest safe order: nothing between the two reads the token,
  the gate asks the vault for it only when it passes work on, and ending it on every failure path instead would need
  a second, untested way to reach the host. A start whose key cannot be kept on a host takes no token and is refused
  with 65. Taking the token is not reached by a unit test: the host's helper is at a fixed root-owned path, and a
  certified start needs a certifier's key from a verified access file; this stays for the machine test.
- A start that fails before podman made its container, kept or not, ends what it made for it, as `task remove` would:
  its account in the project's conversations and the secret that retires it, its mailbox, its guide and its state
  directory. Before, it removed a container that never was and left the rest, with no recorded task a removal could
  reach them by.
- File placement in a container takes the podman stand-in a test names, as `podman exec` already did, so a unit test
  that places a file never reaches the machine's own podman.
- The number a task keeps is read from its durable record when the runtime directory has none, as after a reboot
  before the resume puts the records back, so a continuation takes its own number and refuses another.
- The daemon's `Start` refuses a `subagents` that is no whole number first, before a host's keys are scanned and
  before the runtime is asked for a task's name.
- `CanStart` settles one repository for both the agent it chooses and the limits it reads, as `Start` does: the
  project's own repository of a project that names others is refused as an unknown repository, not for its number,
  and a named repository's agent is taken without a gate too. A named repository is refused with or without a gate,
  and before anything about the agent, its provider or its credential and before the project's host services, in
  `Start`'s order; only a missing project file, a bad task name, a refused value and an undeclared destination come
  first. A repository not named is still asked last, so the choice never hides another refusal.
- The tests that held this hold it now: the kept task whose setup fails runs a helper that must be stopped with it,
  `CanStart` for a named repository without a gate must answer ready, and the task brought back after a reboot must
  come back, exit 0.
