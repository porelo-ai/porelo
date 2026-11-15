# B780 — A Forced Removal Waits for Its Own Container to Go

```json
{
  "$schema": "issue-1.json",
  "id": "B780",
  "title": "A Forced Removal Waits for Its Own Container to Go",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 137,
  "blockedBy": [],
  "covers": "A task removal that asked the runtime to remove its container reads the runtime's answer and waits a bounded time for the container to be gone before it calls the removal failed",
  "openQuestions": 0
}
```

**Why this size.** S: `Podman.remove` reads its two answers, `TaskControl`'s purge check waits a bounded time for
the container to go, and their tests. Estimate: 38 minutes (Core's median for S).

**What must be true.** `porelo project remove <name> --force` and `porelo task remove <task> --force` do not fail
with "still exists" for a container that is on its way out because of the removal itself.

Filed 2026-11-14, from an acceptance leg on Ubuntu: the cleanup of `agent-waiting.feature` ran
`porelo project remove busy --force`, which answered "task porelo-busy_busy was not removed: still running:
container porelo-busy_busy still exists", exit 70, on that one leg only.

## Why

`Podman.remove` runs `podman stop --time 5` and then `podman rm --force` with `runner.run`, and neither answer is
read. `TaskControl`'s purge then asks `stateOf(id)` once, at once, and calls anything but `ABSENT` a survivor.

The stop starts the runtime's own cleanup of the container (conmon's `container cleanup` and Porelo's poststop hook),
which can run alongside the `rm`. Most likely, an `rm` that loses that race fails (the container is locked or in a
state it cannot remove from), or the container is still listed for a moment after it returns. Either way the single
check sees it, the removal reports a container that is in fact going, and `ProjectDeletion` stops with `FAILED` -
leaving the project half removed, though nothing is wrong. Why only one leg: the timing of the runtime's cleanup on
that host.

## Plan

1. `Podman.remove` reads the `rm --force` answer; a refused removal is asked once more (`Podman.forceRemove`) when
   the container stands stopped, its cleanup over.
2. The purge check in `TaskControl` waits a bounded time (a few seconds, polling `stateOf(id)`) for `ABSENT` before
   it names the container a survivor, as `awaitEnd` already does for processes.
3. When it is still there after the wait, the message says why in Porelo's words: the runtime's exit code and,
   when its answer holds one, which of the runtime's fixed reasons it gave (`container state improper`,
   `dependent containers`, ...), never the runtime's line itself, which may hold anything.

## Red-first test

- A runtime stub whose first `rm` fails and whose container is listed once more after it: the removal is clean and
  the project goes. Fails today with "still exists".
- A container that never goes: the removal still fails, after the wait, with the runtime's reason.
- A refusal whose line holds a secret-like value: the answer names the exit code alone.

## Risks

- The wait adds a few seconds to a removal that truly fails; it must stay bounded.

## Blockers

None.

## Acceptance

- The red-first tests pass.
- `agent-waiting.feature` on the VM, twice in one account, both green with no container left.

## Open questions

None.
