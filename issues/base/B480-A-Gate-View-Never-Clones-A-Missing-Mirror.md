# B480 — A Gate View Never Clones A Missing Mirror

**Status:** Review.
**Size:** S - the gate calls stop making a missing mirror; their tests. Estimate 30 minutes.

**What must be true.** Asking what waits at a gate only reads. For a repository this machine holds no mirror of, it
answers that nothing is pending and reaches nothing: no clone from the repository's upstream, no network, no mirror
made. Reviewing, approving or rejecting there is refused, saying that only a task's start makes the mirror.

## Found

- The window asks `Pending` once per repository of a project. Each call opened the gate with `GitGate.initialize()`,
  which clones a missing mirror from the repository's upstream.
- On a host whose project names the central forge over ssh as `upstream`, a person's account holds no host key and no
  access for it, by design. One view of the gate tried a clone per repository, each refused on the host key, one after
  another: a long wait, then an error for every repository the task never touched.
- `porelo gate pending`, `review`, `approve` and `reject` did the same.

## Done

- `PoreloDaemon.gate` and the four commands check that the mirror is there (`GateSupport.holds`, `held`) and never
  call `initialize()`; only a task's start and `gate serve` make a mirror.
- `Pending` without a mirror answers its path and mode and an empty list; the other calls refuse.
- Test: a project whose upstream does not exist and no mirror. `Pending` answers nothing waiting and makes no
  mirror; `Review` is refused. It failed before the change, with the clone's error.
