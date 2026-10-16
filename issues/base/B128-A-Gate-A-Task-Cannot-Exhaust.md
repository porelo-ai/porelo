# B128 — A Gate A Task Cannot Exhaust

**Status:** Review.
**Size:** M - `GitHttpServer` in `gate`, the gate's scope in `app-task`, `doc/security.md` and tests.

**What must be true.** What a task's agent can make its gate do on the host is bounded: how many requests it serves
at once, how much of a push it holds in memory, and how much memory, how many processes and how much CPU the gate and
the `git` it starts may take together. An agent that sends requests in a loop or pushes as much as it can slows its
own gate down, and nothing else on the machine.

## Why

Every task has a gate of its own on the host, reachable only from its container and only with its token - and the
agent holds that token. Read from the code (`GitHttpServer`, 2026-10-08): every request gets a thread of its own, with
no limit on how many run at once and no limit on how fast they come; every request starts a `git upload-pack` or
`git receive-pack` on the host, as the person's user and outside the container's limits (8 GB memory, 2048
processes); and a request's body is read into memory whole, up to 1 GiB. No resource limit for the gate's process was
found. So a few parallel large pushes, or many small requests at once, take memory and processes from `porelod`, from
every other task and from the machine. They reach no data and no key, but they can stop the machine's work.

The same holds for every class with a gate: `guarded` today, and `online` once it pushes through the host ([B127](README.md#b127)).

## Plan

Built and on `origin/main` (`80419bbe`; released in 0.4.1), read in the code at `30c8169b`:

- `GitHttpServer` (`gate/src/main/java/org/fuin/porelo/gate/GitHttpServer.java`) serves at most `AT_ONCE` (4)
  requests, a semaphore; one beyond is answered `503` with `Retry-After` at once. A request's body is spooled to a
  file beside the mirror, not held in memory, and the size limit stays.
- `GateWiring` (`apps/app-task/.../GateWiring.java`) starts each task's gate in a scope of its own
  (`Scope.around`) with `MemoryMax=1G`, `TasksMax=256` and `CPUWeight=50`; every `git` it starts is its child and in
  that scope.
- `doc/security.md`, "What a task can make its gate take", says it.

**Answered:** four requests at once; one scope per task's gate, not one slice for all gates.

The acceptance run on the Ubuntu VM is green on `30c8169b` (220 scenarios); the operator's test is left.

## Acceptance

- **Requests at once:** a gate serves at most a few requests at a time; one beyond that is answered at once with
  `503`, not queued without end. Seen to fail: fifty requests at once from a container start fifty `git` processes.
- **A push is streamed, not held:** a push's body goes to `git receive-pack` as it arrives, or to a file, never into
  memory whole; the size limit stays. Seen to fail: a gate whose memory grows by the size of a large push.
- **The gate in bounds of its own:** the gate and every `git` it starts run in a scope with a memory limit, a process
  limit and a CPU weight below `porelod`'s, the way a task's container has them; exceeding one ends what exceeded it,
  never `porelod` or another task. Seen to fail: a gate whose `git` processes are not in its scope.
- `doc/security.md` says what a task can make its gate take on the host, and where that ends.
